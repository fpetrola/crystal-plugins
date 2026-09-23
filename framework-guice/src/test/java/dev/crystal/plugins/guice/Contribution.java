package dev.crystal.plugins.guice;

import com.google.inject.Module;

import dev.crystal.plugins.api.RoleInterface;

/** A role that is a Guice module: plugins configure the application's own injector with it. */
@RoleInterface
public interface Contribution extends Module {
}
