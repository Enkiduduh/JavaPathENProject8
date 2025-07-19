package com.openclassrooms.tourguide.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import rewardCentral.RewardCentral;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

@Service
public class RewardsService {
    private static final double STATUTE_MILES_PER_NAUTICAL_MILE = 1.15077945;

    // proximity in miles
    private int defaultProximityBuffer = 10;
    private int proximityBuffer = defaultProximityBuffer;
    private int attractionProximityRange = 200;
    private final GpsUtil gpsUtil;
    private final RewardCentral rewardsCentral;

    public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
        this.gpsUtil = gpsUtil;
        this.rewardsCentral = rewardCentral;
    }

    public void setProximityBuffer(int proximityBuffer) {
        System.out.println(">>> SET PROXIMITY BUFFER = " + proximityBuffer);
        this.proximityBuffer = proximityBuffer;
    }

    public void setDefaultProximityBuffer() {
        proximityBuffer = defaultProximityBuffer;
    }

    public void setProximityBufferToMax() {
        this.proximityBuffer = Integer.MAX_VALUE;
    }

    public void calculateRewards(User user) {
        List<VisitedLocation> userLocations = new ArrayList<>(user.getVisitedLocations());
        if (userLocations.isEmpty()) {
            // éventuellement ajouter une localisation par défaut
            return;
        }

        List<Attraction> attractions = gpsUtil.getAttractions();

        int added = 0;
        for (Attraction attraction : attractions) {
            // Ne pas recréer si déjà présente
            boolean already = user.getUserRewards().stream()
                    .anyMatch(r -> r.attraction.attractionId.equals(attraction.attractionId));
            if (already) continue;

            // Vérifier si AU MOINS UNE des visited locations est dans la zone
            for (VisitedLocation visitedLocation : userLocations) {
                double distance = getDistance(attraction, visitedLocation.location);
                if (distance <= proximityBuffer) {
                    int points = getRewardPoints(attraction, user);
                    user.addUserReward(new UserReward(visitedLocation, attraction, points));
                    added++;
                    break; // ← très important : une seule reward par attraction
                }
            }
        }
    }



    public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
        return getDistance(attraction, location) <= attractionProximityRange;
    }

    private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
        return getDistance(attraction, visitedLocation.location) <= proximityBuffer;
    }

    public int getRewardPoints(Attraction attraction, User user) {
        return rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId());
    }

    public double getDistance(Location loc1, Location loc2) {
        double lat1 = Math.toRadians(loc1.latitude);
        double lon1 = Math.toRadians(loc1.longitude);
        double lat2 = Math.toRadians(loc2.latitude);
        double lon2 = Math.toRadians(loc2.longitude);

        double angle = Math.acos(Math.sin(lat1) * Math.sin(lat2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2));

        double nauticalMiles = 60 * Math.toDegrees(angle);
        return STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;
    }

}
