# Change Log
All notable changes to this project will be documented in this
file. This change log follows the conventions of
[keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]
### Added
- Added `:metadata` key as a required value (that may be nil) in the map returned by `get-object`, when successfully executed.
- Added new metadata options for `put-object` method: `:content-type`, `:content-disposition`, `:content-encoding` and `:filename` options.
- Implemented the `rename-object` method.

### Changed
- Upgraded github Actions dependencies.
- Upgraded linting and testing dependencies to latest stable versions, and Java version to 21 (for tests).
- Upgraded Clojure version to 1.12.4 and spec.alpha to 0.5.238.
- Upgraded to object-storage.core 0.1.8
- copy-object no longer returns failure when the source object key and the destination object key are the same value. It does nothing and returns success.

## [0.1.4] - 2024-11-15
### Fixed
- Correctly handle special URL characters when building resource URLs

### Changed
- Updated dependencies to latest stable versions

## [0.1.3] - 2024-04-17
### Fixed
- Fix get-object sometimes returning object as text (Issue #2)
- Don't parse external entities when reading XML

## [0.1.2] - 2024-03-20
### Added
- Add option to get public object url

## [0.1.1] - 2024-03-01
### Fixed
- Fix authentication failing when day of month < 10 [#1]

## [0.1.0] - 2023-10-27
- Initial version

[Unreleased]: https://github.com/gethop-dev/object-storage.azure-blob-storage/compare/v0.1.4...HEAD
[0.1.4]: https://github.com/gethop-dev/object-storage.azure-blob-storage/releases/tag/v0.1.4
[0.1.3]: https://github.com/gethop-dev/object-storage.azure-blob-storage/releases/tag/v0.1.3
[0.1.2]: https://github.com/gethop-dev/object-storage.azure-blob-storage/releases/tag/v0.1.2
[0.1.1]: https://github.com/gethop-dev/object-storage.azure-blob-storage/releases/tag/v0.1.1
[0.1.0]: https://github.com/gethop-dev/object-storage.azure-blob-storage/releases/tag/v0.1.0
