package fi.bizhop.jassu.service;

import fi.bizhop.jassu.model.User;
import fi.bizhop.jassu.security.GoogleAuth;
import fi.bizhop.jassu.security.JWTAuth;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;

@Service
public class AuthService {
    final UserService userService;

    private static final String HEADER_STRING = "Authorization";

    public AuthService(UserService userService) {
        this.userService = userService;
    }

    public User login(HttpServletRequest request) {
        String userEmail = getEmail(request);
        if(userEmail == null) {
            return null;
        }
        else {
            User user = userService.get(userEmail);
            if(user == null) {
                String jwt = JWTAuth.getJwt(userEmail);
                user = new User(userEmail, jwt);
                userService.add(user);
            }
            else {
                String token = request.getHeader(HEADER_STRING);
                String jwt = token.startsWith(JWTAuth.JWT_TOKEN_PREFIX) ? token : JWTAuth.getJwt(userEmail);
                user.setJwt(jwt);
            }
            return user;
        }
    }

    private String getEmail(HttpServletRequest request) {
        String token = request.getHeader(HEADER_STRING);
        if(token == null || token.equals("null")) {
            return null;
        }
        String userEmail = null;
        if(token.startsWith(JWTAuth.JWT_TOKEN_PREFIX)) {
            userEmail = JWTAuth.getUserEmail(token);
        }
        if(userEmail == null) {
            userEmail = GoogleAuth.getUserEmail(token);
        }
        return userEmail;
    }

    public String getEmailFromJWT(HttpServletRequest request) {
        String token = request.getHeader(HEADER_STRING);
        return JWTAuth.getUserEmail(token);
    }
}
