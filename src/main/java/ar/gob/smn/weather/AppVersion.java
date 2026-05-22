package ar.gob.smn.weather;

/** Reads {@code Implementation-Version} from the runnable JAR manifest (Maven {@code project.version}, incl. build segment). */
final class AppVersion {

    private AppVersion() {}

    static String implementationVersion() {
        Package p = WeatherMailApplication.class.getPackage();
        String v = p != null ? p.getImplementationVersion() : null;
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        return "unknown (run from packaged jar for version)";
    }
}
