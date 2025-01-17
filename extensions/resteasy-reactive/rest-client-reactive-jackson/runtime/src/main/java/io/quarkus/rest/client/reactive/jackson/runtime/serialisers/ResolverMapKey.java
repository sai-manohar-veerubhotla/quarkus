package io.quarkus.rest.client.reactive.jackson.runtime.serialisers;

import java.util.Objects;

import jakarta.ws.rs.core.Configuration;

/**
 * Each REST Client can potentially have different providers, so we need to make sure that
 * caching for one client does not affect caching of another
 */
public final class ResolverMapKey {

    private final Class<?> type;
    private final Configuration configuration;

    private final Class<?> restClientClass;

    public ResolverMapKey(Class<?> type, Configuration configuration, Class<?> restClientClass) {
        this.type = type;
        this.configuration = configuration;
        this.restClientClass = restClientClass;
    }

    public Class<?> getType() {
        return type;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public Class<?> getRestClientClass() {
        return restClientClass;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResolverMapKey)) {
            return false;
        }
        ResolverMapKey that = (ResolverMapKey) o;
        return Objects.equals(type, that.type) && Objects.equals(configuration, that.configuration)
                && Objects.equals(restClientClass, that.restClientClass);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, configuration, restClientClass);
    }
}
