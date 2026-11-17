package com.example.shop.mcp.core;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Business area of a tool. At consent the user chooses which areas the AI may use. */
public enum Area {
    CORE, CATALOG, CUSTOMERS, ORDERS, INVENTORY, REPORTS;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** "catalog, orders" → {CATALOG, ORDERS}; unknown names are ignored. */
    public static Set<Area> parse(String csv) {
        Set<Area> areas = EnumSet.noneOf(Area.class);
        if (csv == null) {
            return areas;
        }
        for (String part : csv.split(",")) {
            String p = part.trim().toUpperCase(Locale.ROOT);
            for (Area a : values()) {
                if (a.name().equals(p)) {
                    areas.add(a);
                }
            }
        }
        return areas;
    }
}
