package com.board.spring_leaderboard;

import org.springframework.boot.SpringApplication;

public class TestSpringLeaderboardApplication {

    public static void main(String[] args) {
        SpringApplication.from(SpringLeaderboardApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
