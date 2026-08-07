package cl.timbre.domain;

public enum Ambiente {
    CERTIFICACION("https://maullin.sii.cl"),
    PRODUCCION("https://palena.sii.cl");

    private final String baseUrl;

    Ambiente(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String baseUrl() {
        return baseUrl;
    }
}
