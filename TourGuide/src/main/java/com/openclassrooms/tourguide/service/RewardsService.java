package com.openclassrooms.tourguide.service;

import java.util.*;
import java.util.concurrent.*;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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
    private final int poolCount = 10;
    private final ExecutorService executor;
    // proximity in miles
    private int defaultProximityBuffer = 10;
    private int proximityBuffer = defaultProximityBuffer;
    private int attractionProximityRange = 200;
    private final GpsUtil gpsUtil;
    private final RewardCentral rewardsCentral;

    public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
        this.gpsUtil = gpsUtil;
        this.rewardsCentral = rewardCentral;
        this.executor = Executors.newFixedThreadPool(poolCount);
    }

    @PreDestroy
    public void shutdownExecutor() {
        executor.shutdown();
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

    public void calculateRewards(User user) throws InterruptedException, ExecutionException {
        List<VisitedLocation> visited = new ArrayList<>(user.getVisitedLocations());
        if (visited.isEmpty()) return;

        //  Préparer le set des attractions déjà récompensées
        Set<UUID> rewarded = user.getUserRewards().stream()
                .map(r -> r.attraction.attractionId)
                .collect(Collectors.toSet());

        List<Attraction> attractions = gpsUtil.getAttractions();
        if (attractions.isEmpty()) return;

        // Partitionner les attractions en N pools (ici 10)
        int poolCount = 10;
        List<List<Attraction>> partitions = IntStream.range(0, poolCount)
                .mapToObj(i -> new ArrayList<Attraction>())
                .collect(Collectors.toList());
        for (int idx = 0; idx < attractions.size(); idx++) {
            partitions.get(idx % poolCount).add(attractions.get(idx));
        }

        double buffer = this.proximityBuffer;

        // on soumet sur le pool créé en constructor
        List<Future<List<UserReward>>> futures = new ArrayList<>();
        for (List<Attraction> chunk : partitions) {
            futures.add(executor.submit(() -> {
                List<UserReward> local = new ArrayList<>();
                for (Attraction a : chunk) {
                    if (rewarded.contains(a.attractionId)) continue;
                    for (VisitedLocation vl : visited) {
                        if (getDistance(a, vl.location) <= buffer) {
                            int pts = rewardsCentral.getAttractionRewardPoints(a.attractionId, user.getUserId());
                            local.add(new UserReward(vl, a, pts));
                            break;
                        }
                    }
                }
                return local;
            }));
        }

        for (Future<List<UserReward>> f : futures) {
            for (UserReward r : f.get()) {
                user.addUserReward(r);
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
