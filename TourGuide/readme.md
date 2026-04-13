# Technologies

> Java 17  
> Spring Boot 3.X  
> JUnit 5

# How to have gpsUtil, rewardCentral and tripPricer dependencies available ?

> Run :

```bash
mvn install:install-file -Dfile=libs/gpsUtil.jar -DgroupId=gpsUtil -DartifactId=gpsUtil -Dversion=1.0.0 -Dpackaging=jar
mvn install:install-file -Dfile=libs/RewardCentral.jar -DgroupId=rewardCentral -DartifactId=rewardCentral -Dversion=1.0.0 -Dpackaging=jar
mvn install:install-file -Dfile=libs/TripPricer.jar -DgroupId=tripPricer -DartifactId=tripPricer -Dversion=1.0.0 -Dpackaging=jar
```

# Pipeline CI/CD
> The project is configured to be built and tested using GitHub Actions. The pipeline is triggered on every push to the main branch and on pull requests. It includes the following steps:
>  - Checkout code
>  - Set up JDK 17
>  - Build the project using Maven
>  - Run tests using Maven

# Performance Optimization
To scale up the application and handle a large number of users,we used the following strategies:
- Caching: We implemented caching for frequently accessed data, such as user preferences and attraction information, to reduce the number of database calls and improve response times.
- Asynchronous Processing: We used asynchronous processing for tasks that do not require immediate responses, such as sending notifications and updating user rewards, to free up resources and improve overall performance.
- Load Balancing: We configured load balancing to distribute incoming requests across multiple instances of the application, ensuring that no single instance is overwhelmed and improving the application's ability to handle a large number of users.