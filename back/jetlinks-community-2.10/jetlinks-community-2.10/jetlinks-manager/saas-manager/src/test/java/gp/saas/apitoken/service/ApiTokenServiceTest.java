package gp.saas.apitoken.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiTokenServiceTest {
    @Test
    void generatesHighEntropyPrefixedTokenAndStoresOnlyDigest() {
        String first = ApiTokenService.generateToken();
        String second = ApiTokenService.generateToken();
        assertTrue(first.startsWith("gpl_"));
        assertTrue(first.length() > 40);
        assertNotEquals(first, second);
        assertEquals(64, ApiTokenService.hash(first).length());
        assertEquals(ApiTokenService.hash(first), ApiTokenService.hash(first));
        Set<Character> alphabet = new HashSet<>();
        for (char c : first.substring(4).toCharArray()) alphabet.add(c);
        assertTrue(alphabet.stream().noneMatch(c -> c == '+' || c == '/' || c == '='));
    }
}
