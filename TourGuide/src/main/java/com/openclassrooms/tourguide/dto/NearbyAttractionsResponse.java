package com.openclassrooms.tourguide.dto;

import java.util.List;

public class NearbyAttractionsResponse {
    private String userName;
    private double userLatitude;
    private double userLongitude;
    private List<NearbyAttractionDTO> attractions;

    public NearbyAttractionsResponse(String userName,
                                     double userLatitude,
                                     double userLongitude,
                                     List<NearbyAttractionDTO> attractions) {
        this.userName = userName;
        this.userLatitude = userLatitude;
        this.userLongitude = userLongitude;
        this.attractions = attractions;
    }

    public String getUserName() { return userName; }
    public double getUserLatitude() { return userLatitude; }
    public double getUserLongitude() { return userLongitude; }
    public List<NearbyAttractionDTO> getAttractions() { return attractions; }
}
