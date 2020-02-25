# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.4.1] - 24 February 2020
### Fixed
- Use `offer!` instead of `put!` for emitting events to channels

## [0.4.0] - 24 February 2020
### Added
- **[BREAKING]** a `registry` function. Breaking because `registry` was a var
  containing a registry in a previous version. That var is now `default-registry`.
- `emit-registry-events!` and `emit-events!` functions
- functions for interacting with registries: `add-configuration!`, `find`, `remove!`, and `replace!`
- Tests!
### Changed
- **[BREAKING]** renamed `registry` to `default-registry`
- `circuit-breaker!` can now accept a registry as a param
- `circuit-breaker` will use a default config if no config is provided
- Relaxed required Clojure to 1.5.1 for JDK 8 and documented requirement of Clojure 1.10+ for JDK 9+
- Updated docs and docstrings to reflect new API changes.
- Use test-runner instead of kaocha to support older versions of Clojure
### Removed
- **[BREAKING]** `configure-registry!` function
- **[BREAKING]** specs

## [0.3.0] - 19 February 2020
### Changed
- Updated resilience4j-circuitbreaker 1.3.1
- Added new function for transitioning to new metrics-only state
### Fixed
- A bug preventing reset! from working properly

## [0.2.0] - 27 January 2020
### Changed
- **[BREAKING]** Updated configuration options to reflect resilience4j circuitbreaker 1.0+

## [0.1.0] - 22 July 2019
### Added
- Added initial circuit breaker wrapper implementation
