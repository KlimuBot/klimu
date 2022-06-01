package eus.klimu.klimudesktop.app;

import com.google.gson.Gson;
import eus.klimu.klimudesktop.app.user.AppUser;
import eus.klimu.klimudesktop.connection.RequestMaker;
import eus.klimu.klimudesktop.security.TokenManagement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Collection;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImp implements UserDetailsService {

    private final Gson gson = new Gson();
    private final HttpSession session;
    private final RequestMaker requestMaker = new RequestMaker();

    @Nullable
    private JSONObject getTokensFromServer(String username, String password) {
        ResponseEntity<String> response = requestMaker.doPost(
                RequestMaker.SERVER_LOGIN_URL,
                requestMaker.generateHeaders(MediaType.APPLICATION_FORM_URLENCODED, null),
                requestMaker.generateBody(username, password)
        );
        if (response.getStatusCode().is2xxSuccessful() && response.hasBody()) {
            return new JSONObject(response.getBody());
        }
        return null;
    }

    @Nullable
    private AppUser getUserFromServer(String username, JSONObject tokens) {
        ResponseEntity<String> appUserResponse = requestMaker.doGet(
                RequestMaker.SERVER_USER_URL + username,
                requestMaker.addTokenToHeader(
                        new HttpHeaders(),
                        tokens.getString(TokenManagement.ACCESS_TOKEN),
                        tokens.getString(TokenManagement.REFRESH_TOKEN)
                ), null
        );
        // Check if the user was found.
        if (appUserResponse.getStatusCode().is2xxSuccessful() && appUserResponse.hasBody()) {
            return gson.fromJson(appUserResponse.getBody(), AppUser.class);
        }
        return null;
    }

    private User generateUser(AppUser user) {
        // Get the user roles as SimpleGrantedAuthorities for Spring Security.
        Collection<SimpleGrantedAuthority> authorities = new ArrayList<>();
        if (user.getRoles() != null && !user.getRoles().isEmpty()) {
            user.getRoles().forEach(role -> authorities.add(
                    new SimpleGrantedAuthority(role.getName())
            ));
        }
        // Create a Spring Security user to check on with.
        return new User(
                user.getUsername(),
                user.getPassword(),
                authorities
        );
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String password = (String) session.getAttribute("password");

        if (username != null && password != null) {
            try {
                // Get the tokens for accessing the server.
                JSONObject tokens = getTokensFromServer(username, password);
                if (tokens != null) {
                    if (tokens.has(TokenManagement.ACCESS_TOKEN) && tokens.has(TokenManagement.REFRESH_TOKEN)) {
                        // Get the user from the request.
                        AppUser user = getUserFromServer(username, tokens);

                        if (user != null) {
                            log.info("User {} found on the database", username);
                            return generateUser(user);
                        }
                    }
                    else if (tokens.has(RequestMaker.ERROR_MSG)) {
                        log.error(tokens.getString(RequestMaker.ERROR_MSG));
                        throw new UsernameNotFoundException(tokens.getString(RequestMaker.ERROR_MSG));
                    }
                }
            } catch (RestClientException e) {
                log.error(e.getMessage());
                throw new UsernameNotFoundException(e.getMessage());
            }
        }
        log.error("User with username {} couldn't be found", username);
        throw new UsernameNotFoundException("User with username" + username + "couldn't be found");
    }

}
