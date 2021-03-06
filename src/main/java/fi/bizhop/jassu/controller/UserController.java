package fi.bizhop.jassu.controller;

import fi.bizhop.jassu.exception.UserException;
import fi.bizhop.jassu.model.User;
import fi.bizhop.jassu.model.UserIn;
import fi.bizhop.jassu.service.AuthService;
import fi.bizhop.jassu.service.UserService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@RestController
public class UserController {
    final UserService USER_SERVICE;
    final AuthService AUTH_SERVICE;

    public UserController(UserService userService, AuthService authService) {
        this.USER_SERVICE = userService;
        this.AUTH_SERVICE = authService;
    }

    @RequestMapping(value = "/api/user", method = RequestMethod.PUT, produces = "application/json", consumes = "application/json")
    public @ResponseBody User update(   @RequestBody UserIn userIn,
                                        HttpServletRequest request,
                                        HttpServletResponse response) throws UserException {
        String email = this.AUTH_SERVICE.getEmailFromJWT(request);
        if(email == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        } else {
            response.setStatus(HttpServletResponse.SC_OK);
            return this.USER_SERVICE.updateUser(email, userIn);
        }
    }
}
