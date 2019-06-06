/* 
 * Original work: Copyright (c) 2010-2011 Brigham Young University
 * Modified work: Copyright (c) 2017 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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
package com.xilinx.rapidwright.placer.blockplacer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

/**
 * This class provides methods necessary for determining creating a new point
 * which minimizes the maximum distance from the new point to any point in
 * a given set of points in the plane.  Useful for relocating registers.
 * @author Jaren Lamprecht
 * Created on: Jun 16, 2011
 */
public class SmallestEnclosingCircle {

	/**
	 * Returns a new point which is the center of the smallest enclosing circle
	 * on points.  This minimizes the maximum distance from the new point to any
	 * other point in points.
	 * http://www.personal.kent.edu/~rmuhamma/Compgeometry/MyCG/CG-Applets/Center/centercli.htm
	 * @param points the points in the set to consider.
	 * @return Point, the center of the smallest enclosing circle.
	 */
	public static Point getCenterPoint(HashSet<Point> pointsSet){
		ArrayList<Point> points = new ArrayList<Point>(pointsSet);
		Point center = new Point(-1,-1);
		ArrayList<Point> convexHull = null;
		
		switch(points.size()){
		case 0:
			//returns a bogus point when there are no points in the set.
			return center;
		case 1:
			//returns the only point in the set.
			return points.get(0);
		case 2:
			//returns the midpoint between the only two points in the set.
			Point p1 = points.get(0);
			Point p2 = points.get(1);
			center.x = (p1.x > p2.x) ? (p1.x - p2.x) / 2 + p2.x : (p2.x - p1.x) / 2 + p1.x;
			center.y = (p1.y > p2.y) ? (p1.y - p2.y) / 2 + p2.y : (p2.y - p1.y) / 2 + p1.y;
			return center;
		case 3:
			//three points form a convex hull.  proceed from here.
			convexHull = new ArrayList<Point>();
			convexHull.addAll(points);
			break;
		default:
			//four or more points needs the convex hull to be created.
			convexHull = convexHull(points);
			break;
		}
		
		boolean finished = false;
		boolean useMinPoint = false;
		
		//describes side S of the convex hull
		Point s1 = convexHull.get(0);
		Point s2 = convexHull.get(1);
		Point minPoint = null;
		
		while(!finished){
			double minAngle = Math.PI;
			minPoint = null;
			for(Point v: convexHull){
				if(v.equals(s1) || v.equals(s2)){
					continue;
				}
				//compute the angle subtended by s;
				double subtended = angle(s1, v, s2);
				if(subtended < minAngle){
					minAngle = subtended;
					minPoint = v;
				}
			}
			if(minAngle > (Math.PI / 2)){
				//use the side S to determine the circle
				finished = true;
			}else if(angle(s1, s2, minPoint) > (Math.PI / 2)){
				//this angle is obtuse, set the side S accordingly
				s2 = minPoint;
			}else if(angle(s2, s1, minPoint) > (Math.PI / 2)){
				//this angle is obtuse, set the side S accordingly
				s1 = minPoint;
			}else{
				//use the side S and the minPoint to determine the circle
				finished = true;
				useMinPoint = true;
			}
		}
		
		if(useMinPoint){
			//use the side S and the minPoint to determine the circle
			Point circumcenter = getCircumcenter( s1, s2, minPoint);
			center.x = circumcenter.x;
			center.y = circumcenter.y;
		}else{
			//use the side S to determine the diametric circle
			center.x = (s1.x > s2.x) ? (s1.x - s2.x) / 2 + s2.x : (s2.x - s1.x) / 2 + s1.x;
			center.y = (s1.y > s2.y) ? (s1.y - s2.y) / 2 + s2.y : (s2.y - s1.y) / 2 + s1.y;
		}
		
		return center;
	}
	
	/**
	 * Given a set of points, returns the set of points in the convex hull in
	 * counterclockwise order.
	 * http://en.wikibooks.org/wiki/Algorithm_Implementation/Geometry/Convex_hull/Monotone_chain
	 * @param points the input set of points
	 * @return ArrayList<Point>, the set of points in the convex hull
	 */
	public static ArrayList<Point> convexHull(ArrayList<Point> points){
		ArrayList<Point> convexHull = new ArrayList<Point>();
		
		int n = points.size();
		int k = 0;
		
		Point[] hull = new Point[2*n];
		
		Point[] sortedPoints = new Point[points.size()];
		sortedPoints = points.toArray(sortedPoints);
		Arrays.sort(sortedPoints);
		
		for(int i = 0; i < n; i++){
			while(k >= 2 && crossProduct(hull[k-2], hull[k-1], sortedPoints[i]) <= 0){
				k--;
			}
			hull[k++] = sortedPoints[i];
		}
		for(int i = n-2, t = k+1; i >= 0; i--){
			while(k >= t && crossProduct(hull[k-2], hull[k-1], sortedPoints[i]) <= 0){
				k--;
			}
			hull[k++] = sortedPoints[i];
		}
		
		//only k-1 distinct points.  the kth point is the same as the 1st point
		for(int i = 0; i < k-1; i++){
			convexHull.add(hull[i]);
		}
		
		return convexHull;
	}
	
	/**
	 * Determines the circumcenter of a circle that circumscribes triangle abc.
	 * http://en.wikipedia.org/wiki/Circumscribed_circle
	 * @param a
	 * @param b
	 * @param c
	 * @return Point, the circumcenter
	 */
	public static Point getCircumcenter(Point a, Point b, Point c){
		double d = 2 * ( a.x * ( b.y - c.y ) + b.x * ( c.y - a.y ) + c.x * ( a.y - b.y ) );
		double x = (	( Math.pow(a.y, 2) + Math.pow(a.x, 2) ) * ( b.y - c.y ) +
						( Math.pow(b.y, 2) + Math.pow(b.x, 2) ) * ( c.y - a.y ) +
						( Math.pow(c.y, 2) + Math.pow(c.x, 2) ) * ( a.y - b.y ) ) / d;
		double y = (	( Math.pow(a.y, 2) + Math.pow(a.x, 2) ) * ( c.x - b.x ) +
						( Math.pow(b.y, 2) + Math.pow(b.x, 2) ) * ( a.x - c.x ) +
						( Math.pow(c.y, 2) + Math.pow(c.x, 2) ) * ( b.x - a.x ) ) / d;
		return new Point( (int) x, (int) y);
	}
	
	/**
	 * Performs a 2D cross product of OA and OB vectors.  Returns a positive value if
	 * OAB makes a counter-clockwise turn, negative for a clockwise turn, and zero if
	 * the points are collinear.
	 * http://en.wikibooks.org/wiki/Algorithm_Implementation/Geometry/Convex_hull/Monotone_chain
	 * @param o
	 * @param a
	 * @param b
	 * @return int, the cross product
	 */
	public static int crossProduct(Point o, Point a, Point b){
		return (a.x - o.x) * (b.y - o.y) - (a.y - o.y)* (b.x - o.x); 
	}
	
	public static void printPoints(HashSet<Point> points){
		for(Point p : points){
			System.out.println("\tX: " + p.x + "\tY: " + p.y);
		}
	}
	
	/**
	 * Computes the angle ABC from the points a, b, c.
	 * http://forums.devx.com/archive/index.php/t-154064.html
	 * @param a
	 * @param b
	 * @param c
	 * @return double, the angle
	 */
	public static double angle(Point a, Point b, Point c){
		return Math.acos(dotProduct(a, b, c));
	}
	
	/**
	 * Computes the dot product of the vectors ba, bc from the points a, b, c.
	 * http://forums.devx.com/archive/index.php/t-154064.html
	 * @param a
	 * @param b
	 * @param c
	 * @return double, the dot product
	 */
	public static double dotProduct(Point a, Point b, Point c){
		double v1x = a.x - b.x;
		double v1y = a.y - b.y;
		double v2x = c.x - b.x;
		double v2y = c.y - b.y;
		return ( (v1x * v2x) + (v1y * v2y) ) / ( Math.sqrt( Math.pow(v1x, 2) + Math.pow(v1y, 2) ) * Math.sqrt( Math.pow(v2x, 2) + Math.pow(v2y, 2) ) );
	}
	
	/**
	 * Command line interface for debug.
	 * @param args
	 */
	public static void main(String args[]){
		HashSet<Point> testPoints = new HashSet<Point>();
		testPoints.add(new Point(1,4));
		testPoints.add(new Point(1,1));
		testPoints.add(new Point(2,3));
		testPoints.add(new Point(3,4));
		testPoints.add(new Point(4,2));
		System.out.println("\n\nTEST POINTS:");
		printPoints(testPoints);
		Point center = getCenterPoint(testPoints);
		System.out.println("\n\n\nCENTER POINT: X: " + center.x + " Y: " + center.y);
	}
	
}
