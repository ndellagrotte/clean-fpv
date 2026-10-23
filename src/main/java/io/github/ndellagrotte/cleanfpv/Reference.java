/*
 * Copyright (c) 2026 ndellagrotte
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package io.github.ndellagrotte.cleanfpv;

/**
 * Mod identity. The {@code @{...}} markers are Token Envoy tokens filled in at build time from
 * {@code gradle.properties} / {@code tokens.properties}.
 */
public final class Reference {

    private Reference() {}

    public static final String MOD_ID = "@{MOD_ID}";
    public static final String MOD_NAME = "@{MOD_NAME}";
    public static final String VERSION = "@{MOD_VERSION}";

}
