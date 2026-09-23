package com.example.csv.semicolon;

import com.example.csv.CsvDialect;

/** A sub-plugin: implements a role defined by another plugin. Its dependency on it is derived by the build. */
public class Semicolon implements CsvDialect {

    @Override
    public String separator() {
        return ";";
    }
}
