package com.codearena.support;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal stand-in for a browser: it keeps a cookie jar across requests and echoes the
 * CSRF token back in the header the server expects.
 *
 * <p>This exists so the authentication tests exercise the real mechanism. Spring's
 * {@code MockMvc} offers a {@code .with(csrf())} shortcut that injects a valid token
 * directly, which would make the tests pass even if the server never issued the CSRF
 * cookie at all — exactly the bug most likely to reach production, and exactly the one
 * such a shortcut hides. Everything here goes over real HTTP with real {@code Set-Cookie}
 * handling, so a session that is not genuinely established, or a token that is never
 * delivered, fails the test.
 */
public class BrowserClient {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final TestRestTemplate restTemplate;
    private final Map<String, String> cookieJar = new LinkedHashMap<>();

    public BrowserClient(TestRestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public <T> ResponseEntity<T> get(String path, Class<T> responseType) {
        return exchange(HttpMethod.GET, path, null, responseType);
    }

    public <T> ResponseEntity<T> post(String path, Object body, Class<T> responseType) {
        return exchange(HttpMethod.POST, path, body, responseType);
    }

    public <T> ResponseEntity<T> put(String path, Object body, Class<T> responseType) {
        return exchange(HttpMethod.PUT, path, body, responseType);
    }

    private <T> ResponseEntity<T> exchange(HttpMethod method, String path, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        if (!cookieJar.isEmpty()) {
            headers.add(HttpHeaders.COOKIE, cookieJar.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + "; " + b)
                    .orElseThrow());
        }
        // A browser reads the token from the JavaScript-readable cookie and sends it
        // back as a header. Only a same-origin caller can do this, which is what makes
        // it a CSRF defence.
        String csrfToken = cookieJar.get(CSRF_COOKIE);
        if (csrfToken != null) {
            headers.add(CSRF_HEADER, csrfToken);
        }

        ResponseEntity<T> response =
                restTemplate.exchange(path, method, new HttpEntity<>(body, headers), responseType);
        captureCookies(response.getHeaders());
        return response;
    }

    /** Applies {@code Set-Cookie}, honouring deletions signalled by {@code Max-Age=0}. */
    private void captureCookies(HttpHeaders headers) {
        List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return;
        }
        for (String setCookie : setCookies) {
            String[] parts = setCookie.split(";");
            String[] nameValue = parts[0].split("=", 2);
            if (nameValue.length != 2) {
                continue;
            }
            String name = nameValue[0].trim();
            String value = nameValue[1].trim();

            boolean expired = setCookie.toLowerCase().contains("max-age=0") || value.isEmpty();
            if (expired) {
                cookieJar.remove(name);
            } else {
                cookieJar.put(name, value);
            }
        }
    }

    public boolean hasCookie(String name) {
        return cookieJar.containsKey(name);
    }

    public String cookie(String name) {
        return cookieJar.get(name);
    }

    /** Drops the CSRF token so a request can be made without one. */
    public void forgetCsrfToken() {
        cookieJar.remove(CSRF_COOKIE);
    }
}
