package com.openclassrooms.tourguide.service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;
import org.apache.commons.lang3.tuple.Pair;
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
    private final int poolCount = Runtime.getRuntime().availableProcessors() * 2;
    private final ExecutorService executor;
    private int defaultProximityBuffer = 10;
    private int proximityBuffer = defaultProximityBuffer;
    private final GpsUtil gpsUtil;
    private final RewardCentral rewardsCentral;
    private final Map<UUID, Attraction> attractionMap;
    private final SpatialIndex spatialIndex;
    private final Map<Pair<UUID, UUID>, Integer> rewardCache = new ConcurrentHashMap<>();
    private final List<Attraction> allAttractions;

    public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
        this.gpsUtil = gpsUtil;
        this.rewardsCentral = rewardCentral;
        this.executor = Executors.newWorkStealingPool(poolCount);

        long start = System.currentTimeMillis();
        this.allAttractions = gpsUtil.getAttractions();
        System.out.println("Attractions loaded: " + allAttractions.size() + " in " + (System.currentTimeMillis() - start) + "ms");

        this.attractionMap = new HashMap<>(allAttractions.size());
        for (Attraction attraction : allAttractions) {
            attractionMap.put(attraction.attractionId, attraction);
        }
        this.spatialIndex = new SpatialIndex(allAttractions);
    }

    @PreDestroy
    public void shutdownExecutor() {
        executor.shutdown();
    }

    public void setProximityBuffer(int proximityBuffer) {
        this.proximityBuffer = proximityBuffer;
    }

    public static class UserRewardTiming {
        public final long candidateSearchNanos;
        public final long rewardPointsCallNanos;
        public final long totalNanos;

        public UserRewardTiming(long candidateSearchNanos, long rewardPointsCallNanos, long totalNanos) {
            this.candidateSearchNanos = candidateSearchNanos;
            this.rewardPointsCallNanos = rewardPointsCallNanos;
            this.totalNanos = totalNanos;
        }
    }

    public void setDefaultProximityBuffer() {
        proximityBuffer = defaultProximityBuffer;
    }

    public UserRewardTiming calculateRewards(User user) {
        long startTotal = System.nanoTime();
        List<VisitedLocation> visitedLocations = new ArrayList<>(user.getVisitedLocations());

        if (visitedLocations.isEmpty()) {
            return new UserRewardTiming(0, 0, System.nanoTime() - startTotal);
        }

        Set<UUID> alreadyRewarded = user.getUserRewards().stream()
                .map(r -> r.attraction.attractionId)
                .collect(Collectors.toSet());

        long startCandidateSearch = System.nanoTime();
        Map<UUID, VisitedLocation> candidateAttractions = new ConcurrentHashMap<>();

        // Parallélisation de la recherche des attractions candidates
        List<CompletableFuture<Void>> futures = visitedLocations.parallelStream()
                .map(visitedLocation -> CompletableFuture.runAsync(() -> {
                    List<Attraction> nearbyAttractions = spatialIndex.getNearbyAttractions(
                            visitedLocation.location, proximityBuffer);

                    for (Attraction attraction : nearbyAttractions) {
                        UUID attractionId = attraction.attractionId;
                        if (!alreadyRewarded.contains(attractionId) &&
                                !candidateAttractions.containsKey(attractionId)) {
                            candidateAttractions.put(attractionId, visitedLocation);
                        }
                    }
                }, executor))
                .collect(Collectors.toList());

        // Attendre la fin de toutes les tâches
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        long candidateSearchNanos = System.nanoTime() - startCandidateSearch;

        if (candidateAttractions.isEmpty()) {
            long totalNanos = System.nanoTime() - startTotal;
            return new UserRewardTiming(candidateSearchNanos, 0, totalNanos);
        }

        long startRewardCalls = System.nanoTime();
        List<UUID> attractionIds = new ArrayList<>(candidateAttractions.keySet());
        Map<UUID, Integer> rewardPointsMap = calculateRewardPointsParallel(attractionIds, user.getUserId());
        long rewardPointsCallNanos = System.nanoTime() - startRewardCalls;

        // Ajout des récompenses en dehors du bloc synchronisé
        for (UUID attractionId : attractionIds) {
            Attraction attraction = attractionMap.get(attractionId);
            VisitedLocation visitedLocation = candidateAttractions.get(attractionId);
            user.addUserReward(new UserReward(visitedLocation, attraction, rewardPointsMap.get(attractionId)));
        }

        long totalNanos = System.nanoTime() - startTotal;
        return new UserRewardTiming(candidateSearchNanos, rewardPointsCallNanos, totalNanos);
    }

    public int getRewardPoints(Attraction attraction, User user) {
        Pair<UUID, UUID> key = Pair.of(attraction.attractionId, user.getUserId());
        return rewardCache.computeIfAbsent(key, k ->
                rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId()));
    }

    private Map<UUID, Integer> calculateRewardPointsParallel(List<UUID> attractionIds, UUID userId) {
        // Utilisation de parallelStream pour optimiser les petits lots
        Map<UUID, Integer> rewardPointsMap = attractionIds.parallelStream()
                .collect(Collectors.toConcurrentMap(
                        attractionId -> attractionId,
                        attractionId -> {
                            Pair<UUID, UUID> key = Pair.of(attractionId, userId);
                            return rewardCache.computeIfAbsent(key, k ->
                                    rewardsCentral.getAttractionRewardPoints(attractionId, userId));
                        }
                ));
        return rewardPointsMap;
    }

    // Optimisation de l'index spatial avec grille plus fine et cache de distance
    private class SpatialIndex {
        private static final double CELL_SIZE = 0.01; // Degrés (~1.1 km)
        private final Map<String, List<Attraction>> grid = new ConcurrentHashMap<>();
        private final Map<Pair<Location, Attraction>, Double> distanceCache = new ConcurrentHashMap<>();

        public SpatialIndex(List<Attraction> attractions) {
            for (Attraction attraction : attractions) {
                String cellKey = getCellKey(attraction.latitude, attraction.longitude);
                grid.computeIfAbsent(cellKey, k -> new CopyOnWriteArrayList<>()).add(attraction);
            }
        }

        public List<Attraction> getNearbyAttractions(Location location, double maxDistance) {
            Set<String> cellsToCheck = getRelevantCellKeys(location, maxDistance);
            List<Attraction> candidates = new ArrayList<>();

            for (String cellKey : cellsToCheck) {
                List<Attraction> attractionsInCell = grid.getOrDefault(cellKey, Collections.emptyList());
                for (Attraction attraction : attractionsInCell) {
                    double distance = getCachedDistance(attraction, location);
                    if (distance <= maxDistance) {
                        candidates.add(attraction);
                    }
                }
            }
            return candidates;
        }

        private double getCachedDistance(Attraction attraction, Location location) {
            Pair<Location, Attraction> key = Pair.of(location, attraction);
            return distanceCache.computeIfAbsent(key, k ->
                    getDistance(attraction, location));
        }

        private Set<String> getRelevantCellKeys(Location location, double maxDistance) {
            double lat = location.latitude;
            double lon = location.longitude;
            int cellRadius = (int) Math.ceil(maxDistance / (CELL_SIZE * 69));

            Set<String> cells = ConcurrentHashMap.newKeySet();
            IntStream.rangeClosed(-cellRadius, cellRadius).parallel().forEach(latOffset -> {
                double checkLat = lat + latOffset * CELL_SIZE;
                IntStream.rangeClosed(-cellRadius, cellRadius).forEach(lonOffset -> {
                    double checkLon = lon + lonOffset * CELL_SIZE;
                    cells.add(getCellKey(checkLat, checkLon));
                });
            });
            return cells;
        }

        private String getCellKey(double latitude, double longitude) {
            int latCell = (int) Math.floor(latitude / CELL_SIZE);
            int lonCell = (int) Math.floor(longitude / CELL_SIZE);
            return latCell + "," + lonCell;
        }
    }

    // Optimisation du calcul de distance
    public double getDistance(Location loc1, Location loc2) {
        double lat1 = Math.toRadians(loc1.latitude);
        double lon1 = Math.toRadians(loc1.longitude);
        double lat2 = Math.toRadians(loc2.latitude);
        double lon2 = Math.toRadians(loc2.longitude);

        double deltaLon = lon1 - lon2;
        double y = Math.sin(deltaLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) -
                Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLon);

        double angle = Math.atan2(y, x);
        double nauticalMiles = Math.abs(angle) * 3437.74677; // 60 * 180 / PI
        return STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;
    }
}