/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.xilinx.rapidwright.timing.family;

/**
 * Settings of timing-driven routing that depend on the timing model of a device's series, as opposed
 * to the user's settings in RWRouteConfig.
 */
public final class RouterKnobs {

    /** The maximum criticality of a connection */
    private final float maxCriticality;
    /** The present congestion multiplier of timing-driven routing, or NaN to keep the configured one */
    private final float timingDrivenPresentCongestionMultiplier;
    /** Whether partial routing times all nets of the design rather than only those it routes */
    private final boolean timeAllNetsInPartialRouting;
    /** The best route's delay per INT column over the admissible one, NaN if unknown */
    private final float lookaheadRatioX;
    /** The best route's delay per INT row over the admissible one, NaN if unknown */
    private final float lookaheadRatioY;

    /**
     * The system property that selects the router's lookahead delays per INT column and row:
     * {@link #LOOKAHEAD_ESTIMATOR} (the default) or {@link #LOOKAHEAD_ANISOTROPIC}.
     */
    public static final String LOOKAHEAD_PROPERTY = "rapidwright.rwroute.lookahead";
    /**
     * The delay estimator's admissible lookahead delays per INT column and row (see
     * {@link com.xilinx.rapidwright.timing.delayestimator.DelayEstimator#getAdmissibleLookaheadDelayPerTileX()}),
     * in the units of the routed path's delay cost, so that the estimate never exceeds a route's delay
     */
    public static final String LOOKAHEAD_ESTIMATOR = "estimator";
    /**
     * The best route's delays per INT column and row (the estimator's admissible delays times
     * {@link #getLookaheadRatioX()} and {@link #getLookaheadRatioY()}), weighted by
     * {@link #LOOKAHEAD_WEIGHT_PROPERTY}, in the units of the routed path's delay cost
     */
    public static final String LOOKAHEAD_ANISOTROPIC = "anisotropic";
    /** The system property of the weight of the {@link #LOOKAHEAD_ANISOTROPIC} lookahead (default 1) */
    public static final String LOOKAHEAD_WEIGHT_PROPERTY = "rapidwright.rwroute.lookahead.weight";

    /** The knobs of series without a timing model, whose routing is not timing-driven */
    public static final RouterKnobs DEFAULT = new RouterKnobs(0.99f, Float.NaN, false);

    /**
     * Creates router knobs.
     * @param maxCriticality The maximum criticality of a connection.
     * @param timingDrivenPresentCongestionMultiplier The present congestion multiplier of timing-driven
     *                                                routing, unless the user sets one; NaN to keep
     *                                                RWRouteConfig's default.
     * @param timeAllNetsInPartialRouting Whether partial routing times all nets of the design, so that
     *                                    timing paths through preserved and intra-site nets stay whole.
     */
    public RouterKnobs(float maxCriticality, float timingDrivenPresentCongestionMultiplier,
                       boolean timeAllNetsInPartialRouting) {
        this(maxCriticality, timingDrivenPresentCongestionMultiplier, timeAllNetsInPartialRouting, Float.NaN,
                Float.NaN);
    }

    /**
     * Creates router knobs.
     * @param maxCriticality The maximum criticality of a connection.
     * @param timingDrivenPresentCongestionMultiplier The present congestion multiplier of timing-driven
     *                                                routing, unless the user sets one; NaN to keep
     *                                                RWRouteConfig's default.
     * @param timeAllNetsInPartialRouting Whether partial routing times all nets of the design, so that
     *                                    timing paths through preserved and intra-site nets stay whole.
     * @param lookaheadRatioX The best route's delay per INT column over the delay estimator's
     *                        admissible one, NaN if unknown.
     * @param lookaheadRatioY Likewise per INT row.
     */
    public RouterKnobs(float maxCriticality, float timingDrivenPresentCongestionMultiplier,
                       boolean timeAllNetsInPartialRouting, float lookaheadRatioX, float lookaheadRatioY) {
        this.maxCriticality = maxCriticality;
        this.timingDrivenPresentCongestionMultiplier = timingDrivenPresentCongestionMultiplier;
        this.timeAllNetsInPartialRouting = timeAllNetsInPartialRouting;
        this.lookaheadRatioX = lookaheadRatioX;
        this.lookaheadRatioY = lookaheadRatioY;
    }

    /**
     * Checks if the {@link #LOOKAHEAD_ANISOTROPIC} lookahead is selected.
     * @return True if {@link #LOOKAHEAD_PROPERTY} is {@link #LOOKAHEAD_ANISOTROPIC}.
     * @throws IllegalArgumentException If the property has another value than the two.
     */
    public static boolean isAnisotropicLookaheadSelected() {
        String value = System.getProperty(LOOKAHEAD_PROPERTY, LOOKAHEAD_ESTIMATOR);
        if (value.equals(LOOKAHEAD_ANISOTROPIC)) {
            return true;
        }
        if (!value.equals(LOOKAHEAD_ESTIMATOR)) {
            throw new IllegalArgumentException("Unknown " + LOOKAHEAD_PROPERTY + " value '" + value
                    + "', expected " + LOOKAHEAD_ESTIMATOR + " or " + LOOKAHEAD_ANISOTROPIC);
        }
        return false;
    }

    /**
     * @return The weight of the {@link #LOOKAHEAD_ANISOTROPIC} lookahead ({@link #LOOKAHEAD_WEIGHT_PROPERTY}).
     */
    public static float getLookaheadWeight() {
        return Float.parseFloat(System.getProperty(LOOKAHEAD_WEIGHT_PROPERTY, "1"));
    }

    /**
     * Checks if the best route's delays per INT column and row are known, see
     * {@link #getLookaheadRatioX()}.
     * @return True if they are.
     */
    public boolean hasLookaheadRatios() {
        return !Float.isNaN(lookaheadRatioX) && !Float.isNaN(lookaheadRatioY);
    }

    /**
     * @return The best route's delay per INT column (fanout 1, slice to slice, on an empty device)
     * over the delay estimator's admissible one, NaN if unknown.
     */
    public float getLookaheadRatioX() {
        return lookaheadRatioX;
    }

    /**
     * @return The best route's delay per INT row over the delay estimator's admissible one, NaN if
     * unknown.
     */
    public float getLookaheadRatioY() {
        return lookaheadRatioY;
    }

    /**
     * @return The maximum criticality of a connection.
     */
    public float getMaxCriticality() {
        return maxCriticality;
    }

    /**
     * Checks if timing-driven routing should use its own present congestion multiplier, see
     * {@link #getTimingDrivenPresentCongestionMultiplier()}.
     * @return True if it should, unless the user set one.
     */
    public boolean hasTimingDrivenPresentCongestionMultiplier() {
        return !Float.isNaN(timingDrivenPresentCongestionMultiplier);
    }

    /**
     * @return The present congestion multiplier of timing-driven routing, or NaN to keep the
     * configured one.
     */
    public float getTimingDrivenPresentCongestionMultiplier() {
        return timingDrivenPresentCongestionMultiplier;
    }

    /**
     * @return True if partial routing times all nets of the design rather than only those it routes.
     */
    public boolean isTimeAllNetsInPartialRouting() {
        return timeAllNetsInPartialRouting;
    }

    /** System property overriding {@link #getMaxCriticality()}, for tuning */
    public static final String MAX_CRITICALITY_PROPERTY = "rapidwright.rwroute.maxCriticality";
    /**
     * System property overriding {@link #getTimingDrivenPresentCongestionMultiplier()} (NaN for
     * RWRouteConfig's default), for tuning
     */
    public static final String TD_PRESENT_CONGESTION_MULTIPLIER_PROPERTY =
            "rapidwright.rwroute.timingDrivenPresentCongestionMultiplier";
    /** System property overriding {@link #isTimeAllNetsInPartialRouting()}, for tuning */
    public static final String TIME_ALL_NETS_PROPERTY = "rapidwright.rwroute.timeAllNetsInPartialRouting";

    /**
     * Gets these knobs with the values that the tuning system properties
     * ({@link #MAX_CRITICALITY_PROPERTY}, {@link #TD_PRESENT_CONGESTION_MULTIPLIER_PROPERTY},
     * {@link #TIME_ALL_NETS_PROPERTY}) set.
     * @return These knobs if no such property is set, else new knobs.
     */
    public RouterKnobs withOverrides() {
        String maxCrit = System.getProperty(MAX_CRITICALITY_PROPERTY);
        String multiplier = System.getProperty(TD_PRESENT_CONGESTION_MULTIPLIER_PROPERTY);
        String timeAll = System.getProperty(TIME_ALL_NETS_PROPERTY);
        if (maxCrit == null && multiplier == null && timeAll == null) {
            return this;
        }
        return new RouterKnobs(maxCrit == null ? maxCriticality : Float.parseFloat(maxCrit),
                multiplier == null ? timingDrivenPresentCongestionMultiplier : Float.parseFloat(multiplier),
                timeAll == null ? timeAllNetsInPartialRouting : Boolean.parseBoolean(timeAll),
                lookaheadRatioX, lookaheadRatioY);
    }

    @Override
    public String toString() {
        return "maxCriticality " + maxCriticality + ", timing-driven present congestion multiplier "
                + timingDrivenPresentCongestionMultiplier + ", time all nets in partial routing "
                + timeAllNetsInPartialRouting;
    }
}
