package com.openclassrooms.tourguide.user;

import gpsUtil.location.VisitedLocation;
import lombok.Data;
import tripPricer.Provider;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
@Data
public class User {
    private final UUID userId;
    private final String userName;
    private String phoneNumber;
    private String emailAddress;
    private Date latestLocationTimestamp;
    private List<VisitedLocation> visitedLocations = new ArrayList<>();
    private List<UserReward> userRewards = new ArrayList<>();
    private UserPreferences userPreferences = new UserPreferences();
    private List<Provider> tripDeals = new ArrayList<>();

    public User(UUID userId, String userName, String phoneNumber, String emailAddress) {
        this.userId = userId;
        this.userName = userName;
        this.phoneNumber = phoneNumber;
        this.emailAddress = emailAddress;
    }

    public void addToVisitedLocations(VisitedLocation visitedLocation) {
        visitedLocations.add(visitedLocation);
    }

    public void clearVisitedLocations() {
        visitedLocations.clear();
    }

    //The issue seems to be the if condition that checks if the user has already received a reward for the attraction.
// The condition is currently checking if there are no rewards for the attraction, but it should be checking if there is already a reward for the attraction.
// This can be fixed by changing the condition to check if there is already a reward for the attraction, and if so, it should not add a new reward to the user's rewards list.
    public void addUserReward(UserReward userReward) {
        // if (userRewards.stream().filter(r -> !r.attraction.attractionName.equals(userReward.attraction)).count() == 0) {
        if (userRewards.stream().filter(reward -> reward.attraction.attractionName.equals(userReward.attraction.attractionName)).count() == 0) {
            userRewards.add(userReward);
        }
    }


    public VisitedLocation getLastVisitedLocation() {
        return visitedLocations.get(visitedLocations.size() - 1);
    }

}
