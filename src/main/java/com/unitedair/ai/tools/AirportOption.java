package com.unitedair.ai.tools;

/** Current simulator airport returned from the database, never from a UI constant. */
public record AirportOption(
        String code,
        String city,
        String name,
        String country,
        boolean domestic) { }
