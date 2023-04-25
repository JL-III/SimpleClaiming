/*
    GriefPrevention Server Plugin for Minecraft
    Copyright (C) 2012 Ryan Hamshire

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.ryanhamshire.GriefPrevention.visualization;

import org.bukkit.block.data.BlockData;

/**
 * @deprecated {@link me.ryanhamshire.GriefPrevention.visualization.VisualizationType}
 */
@Deprecated(forRemoval = true, since = "16.18")
public enum VisualizationTypeDep {
    Claim,
    Subdivision,
    ErrorClaim,
    RestoreNature,
    AdminClaim;

    @Deprecated(forRemoval = true, since = "16.18")
    VisualizationType convert()
    {
        return switch (this)
                {
                    case Claim -> me.ryanhamshire.GriefPrevention.visualization.VisualizationType.CLAIM;
                    case Subdivision -> me.ryanhamshire.GriefPrevention.visualization.VisualizationType.SUBDIVISION;
                    case ErrorClaim -> me.ryanhamshire.GriefPrevention.visualization.VisualizationType.CONFLICT_ZONE;
                    case RestoreNature -> me.ryanhamshire.GriefPrevention.visualization.VisualizationType.NATURE_RESTORATION_ZONE;
                    case AdminClaim -> me.ryanhamshire.GriefPrevention.visualization.VisualizationType.ADMIN_CLAIM;
                };
    }

    @Deprecated(forRemoval = true, since = "16.18")
    static me.ryanhamshire.GriefPrevention.visualization.VisualizationType ofBlockData(BlockData accent) {
        return switch (accent.getMaterial()) {
            case WHITE_WOOL -> me.ryanhamshire.GriefPrevention.visualization.VisualizationType.SUBDIVISION;
            case NETHERRACK -> me.ryanhamshire.GriefPrevention.visualization.VisualizationType.CONFLICT_ZONE;
            case DIAMOND_BLOCK -> me.ryanhamshire.GriefPrevention.visualization.VisualizationType.NATURE_RESTORATION_ZONE;
            case PUMPKIN -> me.ryanhamshire.GriefPrevention.visualization.VisualizationType.ADMIN_CLAIM;
            default -> me.ryanhamshire.GriefPrevention.visualization.VisualizationType.CLAIM;
        };
    }

}