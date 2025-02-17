package me.ryanhamshire.GriefPrevention.visualization;

/**
 * Constants for types of boundaries.
 */
public enum VisualizationType {
    /** Boundaries for a user claim. */
    CLAIM,
    /** Boundaries for an administrative claim. */
    ADMIN_CLAIM,
    /** Boundaries for a claim subdivision. */
    SUBDIVISION,
    /** Boundaries for a new claim area. */
    INITIALIZE_ZONE,
    /** Boundaries for a conflicting area. */
    CONFLICT_ZONE,
    /** Boundaries for area in which nature has been restored. */
    NATURE_RESTORATION_ZONE

}
