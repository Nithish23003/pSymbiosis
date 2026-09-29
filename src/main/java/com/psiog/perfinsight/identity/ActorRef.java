package com.psiog.perfinsight.identity;

/** Who did something, as reported by a tool. */
public record ActorRef(String externalId, String username, String email, String displayName) {
    public boolean isEmpty() {
        return blank(externalId) && blank(username) && blank(email);
    }

    public String key() {
        if (!blank(externalId)) return externalId;
        if (!blank(username)) return username;
        return email;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
