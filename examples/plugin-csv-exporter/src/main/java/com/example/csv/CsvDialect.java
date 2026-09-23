package com.example.csv;

import dev.crystal.plugins.api.RoleInterface;

/** A role defined by this plugin, for its own sub-plugins: how to separate fields. */
@RoleInterface
public interface CsvDialect {
    String separator();
}
