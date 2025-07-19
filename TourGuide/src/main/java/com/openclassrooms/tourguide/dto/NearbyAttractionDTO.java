package com.openclassrooms.tourguide.dto;

public class NearbyAttractionDTO {
    private String attractionName;
    private double attractionLatitude;
    private double attractionLongitude;
    private double userLatitude;
    private double userLongitude;
    private double distanceInMiles;
    private int rewardPoints;

    public NearbyAttractionDTO(String attractionName,
                               double attractionLatitude,
                               double attractionLongitude,
                               double userLatitude,
                               double userLongitude,
                               double distanceInMiles,
                               int rewardPoints) {
        this.attractionName = attractionName;
        this.attractionLatitude = attractionLatitude;
        this.attractionLongitude = attractionLongitude;
        this.userLatitude = userLatitude;
        this.userLongitude = userLongitude;
        this.distanceInMiles = distanceInMiles;
        this.rewardPoints = rewardPoints;
    }

    public String getAttractionName() { return attractionName; }
    public double getAttractionLatitude() { return attractionLatitude; }
    public double getAttractionLongitude() { return attractionLongitude; }
    public double getUserLatitude() { return userLatitude; }
    public double getUserLongitude() { return userLongitude; }
    public double getDistanceMiles() { return distanceInMiles; }
    public int getRewardPoints() { return rewardPoints; }
}
