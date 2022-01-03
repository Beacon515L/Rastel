-- phpMyAdmin SQL Dump
-- version 4.6.6deb5ubuntu0.5
-- https://www.phpmyadmin.net/
--
-- Host: localhost:3306
-- Generation Time: Jan 03, 2022 at 05:34 PM
-- Server version: 5.7.36-0ubuntu0.18.04.1
-- PHP Version: 7.2.24-0ubuntu0.18.04.10

SET FOREIGN_KEY_CHECKS=0;
SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
SET time_zone = "+00:00";


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;

--
-- Database: `contact_trace`
--

-- --------------------------------------------------------

--
-- Table structure for table `location_recording`
--

CREATE TABLE `location_recording` (
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `cantor_coordinates` bigint(20) UNSIGNED NOT NULL COMMENT 'https://en.wikipedia.org/wiki/Pairing_function#Cantor_pairing_function',
  `cantor_quadrant` tinyint(1) UNSIGNED NOT NULL COMMENT '0 = N/E, 1 = N/W, 2 = S/W, 3 = S/E',
  `recorded_date_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `correlated_ind` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0: Uncorrelated (default). Retain until next cron run. 1: Correlated. Retain for incubation window. 2: Flagged. 3: Notified.'
) ENGINE=InnoDB DEFAULT CHARSET=latin1;

--
-- RELATIONS FOR TABLE `location_recording`:
--   `user_id`
--       `user` -> `id`
--

-- --------------------------------------------------------

--
-- Table structure for table `user`
--

CREATE TABLE `user` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `email` varchar(255) NOT NULL,
  `password_hash` varchar(255) NOT NULL,
  `verified_email` tinyint(1) NOT NULL DEFAULT '0',
  `timezone` varchar(255) NOT NULL DEFAULT 'UTC'
) ENGINE=InnoDB DEFAULT CHARSET=latin1;

--
-- RELATIONS FOR TABLE `user`:
--

-- --------------------------------------------------------

--
-- Table structure for table `user_test`
--

CREATE TABLE `user_test` (
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `test_type` tinyint(3) UNSIGNED DEFAULT NULL COMMENT 'NULL = Unknown, 1 = Rapid Antigen, 2 = PCR, 3 = Antibody',
  `positive_test` tinyint(1) NOT NULL COMMENT 'Ignore inconclusive tests',
  `time_taken` datetime NOT NULL,
  `time_result_received` datetime NOT NULL,
  `time_departing_isolation` timestamp NULL DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=latin1;

--
-- RELATIONS FOR TABLE `user_test`:
--   `user_id`
--       `user` -> `id`
--

--
-- Indexes for dumped tables
--

--
-- Indexes for table `location_recording`
--
ALTER TABLE `location_recording`
  ADD PRIMARY KEY (`user_id`,`recorded_date_time`),
  ADD KEY `status_then_date` (`correlated_ind`,`recorded_date_time`,`user_id`) USING BTREE,
  ADD KEY `coords_then_date` (`cantor_coordinates`,`cantor_quadrant`,`recorded_date_time`) USING BTREE;

--
-- Indexes for table `user`
--
ALTER TABLE `user`
  ADD PRIMARY KEY (`id`),
  ADD KEY `email` (`verified_email`,`email`) USING BTREE;

--
-- Indexes for table `user_test`
--
ALTER TABLE `user_test`
  ADD PRIMARY KEY (`user_id`,`time_taken`),
  ADD KEY `positive_test` (`positive_test`,`time_taken`);

--
-- AUTO_INCREMENT for dumped tables
--

--
-- AUTO_INCREMENT for table `user`
--
ALTER TABLE `user`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT;
--
-- Constraints for dumped tables
--

--
-- Constraints for table `location_recording`
--
ALTER TABLE `location_recording`
  ADD CONSTRAINT `location_recording_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE ON UPDATE CASCADE;

--
-- Constraints for table `user_test`
--
ALTER TABLE `user_test`
  ADD CONSTRAINT `user_test_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE ON UPDATE CASCADE;
SET FOREIGN_KEY_CHECKS=1;

/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
