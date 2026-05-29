package de.dfki.sds.aticserver;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import de.dfki.sds.atic.ac.User;
import java.util.Date;
import java.util.Objects;
import org.json.JSONObject;

/**
 *
 */
public class JwtUtil {

    private static final String SECRET =
        Objects.requireNonNull(
            System.getenv("JWT_SECRET"),
            "JWT_SECRET environment variable missing"
        );
    private static final Algorithm alg = Algorithm.HMAC256(SECRET);

    @Deprecated
    public static String createToken(String username) {
        return JWT.create()
                .withSubject(username)
                .withIssuedAt(new Date())
                .withExpiresAt(new Date(System.currentTimeMillis() + 86400000)) // expires in 24h
                .sign(alg);
    }

    public static String createToken(User user) {
        return JWT.create()
                .withSubject(user.getUsername())
                .withPayload(user.toMap())
                .withIssuedAt(new Date())
                .withExpiresAt(new Date(System.currentTimeMillis() + 86400000)) // expires in 24h
                .sign(alg);
    }

    public static JSONObject validateToken(String token) throws JWTVerificationException {
        DecodedJWT decoded = JWT.require(alg).build().verify(token);

        JSONObject json = new JSONObject();

        // read all claims
        for (String claimName : decoded.getClaims().keySet()) {
            Object value = decoded.getClaim(claimName).as(Object.class);
            json.put(claimName, value);
        }

        return json;
    }
}
