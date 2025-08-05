package com.openclassrooms.tourguide.service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

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

    // cache global (userId, attractionId) -> points
    private final ConcurrentMap<UUID, Integer> attractionRewardCache = new ConcurrentHashMap<>();

    // buffer
    private final int defaultProximityBuffer = 10;
    private int proximityBuffer = defaultProximityBuffer;

    private final GpsUtil gpsUtil;
    private final RewardCentral rewardsCentral;

    // Préchargez et conservez la liste des attractions une seule fois
    private final List<Attraction> allAttractions;

    public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
        this.gpsUtil = gpsUtil;
        this.rewardsCentral = rewardCentral;

        // Charge une fois la liste
        this.allAttractions = gpsUtil.getAttractions();

        // Pré‐chargement des points pour chaque attraction (une seule fois)
        allAttractions.forEach(a ->
                attractionRewardCache.put(
                        a.attractionId,
                        rewardsCentral.getAttractionRewardPoints(a.attractionId, UUID.randomUUID())
                )
        );
    }

    public void setProximityBuffer(int proximityBuffer) {
        this.proximityBuffer = proximityBuffer;
    }

    public void setDefaultProximityBuffer() {
        this.proximityBuffer = defaultProximityBuffer;
    }

    public void calculateRewards(User user) {
        List<VisitedLocation> visited = user.getVisitedLocations();
        if (visited.isEmpty()) return;

        // éviter de dupliquer les récompenses
        Set<UUID> already = user.getUserRewards().stream()
                .map(r -> r.attraction.attractionId)
                .collect(Collectors.toSet());

        for (VisitedLocation vl : visited) {
            Location loc = vl.location;

            // on parcourt toutes les attractions, mais getRewardPoints ne fait qu'une lecture de cache
            for (Attraction a : allAttractions) {
                if (already.contains(a.attractionId)) continue;

                // un seul calcul de distance
                if (getDistance(a, loc) <= proximityBuffer) {
                    int pts = attractionRewardCache.get(a.attractionId);
                    user.addUserReward(new UserReward(vl, a, pts));
                    already.add(a.attractionId);
                }
            }
        }
    }

    private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
        return !(getDistance(attraction, visitedLocation.location) > proximityBuffer);
    }

    public int getRewardPoints(Attraction attraction, User user) {
        return attractionRewardCache.get(attraction.attractionId);
    }

    public double getDistance(Location loc1, Location loc2) {
        double lat1 = Math.toRadians(loc1.latitude);
        double lon1 = Math.toRadians(loc1.longitude);
        double lat2 = Math.toRadians(loc2.latitude);
        double lon2 = Math.toRadians(loc2.longitude);

        double angle = Math.acos(Math.sin(lat1) * Math.sin(lat2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2));

        double nauticalMiles = 60 * Math.toDegrees(angle);
        double statuteMiles = STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;
        return statuteMiles;
    }
}
