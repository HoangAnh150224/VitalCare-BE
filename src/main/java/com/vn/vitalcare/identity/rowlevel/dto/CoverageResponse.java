package com.vn.vitalcare.identity.rowlevel.dto;

import java.util.List;

/**
 * The role-by-action grid, per resource.
 *
 * <p>What turns a policy gap from a default somebody guessed at into a red cell
 * somebody can see. A resource on
 * {@link com.vn.vitalcare.share.security.rowlevel.DefaultScope#FULL} with a role
 * that has no scope is not narrowed for that role at all — the price of being
 * able to switch a running resource on without breaking anyone — and this is
 * where that price is shown rather than hidden.
 */
public record CoverageResponse(List<Resource> resources) {

    public record Resource(String resource, String defaultScope, List<Cell> cells) {
    }

    /**
     * @param risk {@code OK} when a scope exists; {@code UNRESTRICTED} when none
     *             does and the default is {@code FULL}; {@code CLOSED} when none
     *             does and the default is {@code NONE}. Only the middle one is a
     *             problem, and only it is coloured
     */
    public record Cell(String role, String action, boolean hasScope, String risk) {
    }
}
