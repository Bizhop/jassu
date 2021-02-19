package fi.bizhop.jassu.util;

import fi.bizhop.jassu.db.UserDB;
import fi.bizhop.jassu.model.User;

import java.util.Optional;

public class UserTestUtil {
    public static final String TEST_USER_EMAIL = "user@mock.com";

    public static UserDB getTestUserDB(String email) {
        return new UserDB(getTestUser(email));
    }

    public static User getTestUser(String email) {
        return new User(email, "");
    }
}
