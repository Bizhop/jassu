package fi.bizhop.jassu.service;

import fi.bizhop.jassu.models.User;
import fi.bizhop.jassu.security.GoogleAuth;
import fi.bizhop.jassu.security.JWTAuth;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;

@Service
public class AuthService {
    @Autowired
    UserService userService;

    private static final String HEADER_STRING = "Authorization";
    
    public User login(HttpServletRequest request) throws Exception {
        String userEmail = getEmail(request);
        if(userEmail == null) {
            return null;
        }
        else {
            if(userService.get(userEmail) == null) {
                String jwt = JWTAuth.getJwt(userEmail);
                User user = new User(userEmail, jwt);
                userService.add(user);
                return user;
            }
            else {
                return userService.get(userEmail);
            }
        }
    }

    private String getEmail(HttpServletRequest request) throws Exception {
        String token = request.getHeader(HEADER_STRING);
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
