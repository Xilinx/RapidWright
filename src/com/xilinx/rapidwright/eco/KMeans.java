/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Advanced Research and Development.
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

package com.xilinx.rapidwright.eco;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.xilinx.rapidwright.placer.blockplacer.Point;

/**
 * Simple KMeans implementation to be used for partitioning sinks of a net.
 */
public class KMeans {

    private static Point[] initializeCentroids(Collection<Point> points, int k) {
        Point[] centroids = new Point[k];
        // Randomly initialize centroids to points within the min/max range of the data
        // points
        int minX = Integer.MAX_VALUE;
        int maxX = 0;
        int minY = Integer.MAX_VALUE;
        int maxY = 0;

        for (Point p : points) {
            if (p.x < minX)
                minX = p.x;
            if (p.x > maxX)
                maxX = p.x;
            if (p.y < minY)
                minY = p.y;
            if (p.y > maxY)
                maxY = p.y;
        }

        int xRange = maxX - minX;
        int yRange = maxY - minY;

        Random random = new Random();
        for (int i = 0; i < k; i++) {
            int x = random.nextInt(xRange) + minX;
            int y = random.nextInt(yRange) + minY;
            centroids[i] = new Point(x, y);
        }

        return centroids;
    }

    private static int getNearestCentroidIdx(Point point, Point[] centroids) {
        int nearestCentroidIdx = -1;
        int nearestCentroidDist = Integer.MAX_VALUE;
        for (int i = 0; i < centroids.length; i++) {
            int dist = point.getManhattanDistance(centroids[i]);
            if (dist < nearestCentroidDist) {
                nearestCentroidDist = dist;
                nearestCentroidIdx = i;
            }
        }
        return nearestCentroidIdx;
    }

    public static Point calculateCentroid(List<Point> cluster) {
        Point centroid = new Point(0, 0);
        for (Point p : cluster) {
            centroid.x += p.x;
            centroid.y += p.y;
        }
        centroid.x /= cluster.size();
        centroid.y /= cluster.size();
        return centroid;
    }

    /**
     * Given a collection of points, this will partition the point set into k
     * clusters.
     * 
     * @param points   The set of points to partition.
     * @param k        The number of desired clusters
     * @param maxIters The maximum number of iterations to run before achieving
     *                 convergence.
     * @return A map of centroids to a list of the respective points in their
     *         cluster.
     */
    public static Map<Point, List<Point>> kmeansClustering(Collection<Point> points, int k, int maxIters) {
        // Generate random centroids within point ranges
        Point[] centroids = initializeCentroids(points, k);

        List<List<Point>> clusters = null;
        for (int m = 0; m < maxIters; m++) {
            // Assign points to nearest centroid (Manhattan distance)
            clusters = new ArrayList<>(k);
            for (int i = 0; i < k; i++) {
                clusters.add(new ArrayList<>(points.size() / k));
            }
            for (Point point : points) {
                int idx = getNearestCentroidIdx(point, centroids);
                clusters.get(idx).add(point);
            }

            // Calculate new centroids based on current cluster assignments
            Point[] newCentroids = new Point[k];
            for (int i = 0; i < k; i++) {
                List<Point> cluster = clusters.get(i);
                newCentroids[i] = cluster.isEmpty() ? centroids[i] : calculateCentroid(cluster);
            }

            // Check to see if the centroids have settled
            boolean converged = Arrays.deepEquals(centroids, newCentroids);
            centroids = newCentroids;
            if (converged) {
                break;
            }
        }

        Map<Point, List<Point>> centroidClusterMap = new HashMap<>();
        for (int i = 0; i < k; i++) {
            centroidClusterMap.put(centroids[i], clusters.get(i));
        }

        return centroidClusterMap;
    }
}
