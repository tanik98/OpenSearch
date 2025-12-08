# CHANGELOG
All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). See the [CONTRIBUTING guide](./CONTRIBUTING.md#Changelog) for instructions on how to add changelog entries.

## [Unreleased 3.x]
### Added
- Add ProfilingWrapper interface for plugin access to delegates in profiling decorators ([#20607](https://github.com/opensearch-project/OpenSearch/pull/20607))
- Support expected cluster name with validation in CCS Sniff mode ([#20532](https://github.com/opensearch-project/OpenSearch/pull/20532))
- Choose the best performing node when writing with append-only index ([#20065](https://github.com/opensearch-project/OpenSearch/pull/20065))
- Add security policy to allow `accessUnixDomainSocket` in `transport-grpc` module ([#20463](https://github.com/opensearch-project/OpenSearch/pull/20463), [#20649](https://github.com/opensearch-project/OpenSearch/pull/20649))
- Add range validations in query builder and field mapper ([#20497](https://github.com/opensearch-project/OpenSearch/issues/20497))
- [Workload Management] Enhance Scroll API support for autotagging ([#20151](https://github.com/opensearch-project/OpenSearch/pull/20151))
- Add indices to search request slowlog ([#20588](https://github.com/opensearch-project/OpenSearch/pull/20588))

### Changed
- Move Randomness from server to libs/common ([#20570](https://github.com/opensearch-project/OpenSearch/pull/20570))
- Use env variable (OPENSEARCH_FIPS_MODE) to enable opensearch to run in FIPS enforced mode instead of checking for existence of bcFIPS jars ([#20625](https://github.com/opensearch-project/OpenSearch/pull/20625))
- Combining filter rewrite and skip list to optimize sub aggregation([#19573](https://github.com/opensearch-project/OpenSearch/pull/19573))
- Faster `terms` query creation for `keyword` field with index and docValues enabled ([#19350](https://github.com/opensearch-project/OpenSearch/pull/19350))
- Refactor to move prepareIndex and prepareDelete methods to Engine class ([#19551](https://github.com/opensearch-project/OpenSearch/pull/19551))
- Omit maxScoreCollector in SimpleTopDocsCollectorContext when concurrent segment search enabled ([#19584](https://github.com/opensearch-project/OpenSearch/pull/19584))
- Onboarding new maven snapshots publishing to s3 ([#19619](https://github.com/opensearch-project/OpenSearch/pull/19619))
- Remove MultiCollectorWrapper and use MultiCollector in Lucene instead ([#19595](https://github.com/opensearch-project/OpenSearch/pull/19595))
- Change implementation for `percentiles` aggregation for latency improvement ([#19648](https://github.com/opensearch-project/OpenSearch/pull/19648))
- Wrap checked exceptions in painless.DefBootstrap to support JDK-25 ([#19706](https://github.com/opensearch-project/OpenSearch/pull/19706))
- Refactor the ThreadPoolStats.Stats class to use the Builder pattern instead of constructors ([#19317](https://github.com/opensearch-project/OpenSearch/pull/19317))
- Refactor the IndexingStats.Stats class to use the Builder pattern instead of constructors ([#19306](https://github.com/opensearch-project/OpenSearch/pull/19306))
- Remove FeatureFlag.MERGED_SEGMENT_WARMER_EXPERIMENTAL_FLAG. ([#19715](https://github.com/opensearch-project/OpenSearch/pull/19715))
- Replace java.security.AccessController with org.opensearch.secure_sm.AccessController in sub projects with SocketAccess class ([#19803](https://github.com/opensearch-project/OpenSearch/pull/19803))
- Replace java.security.AccessController with org.opensearch.secure_sm.AccessController in discovery plugins ([#19802](https://github.com/opensearch-project/OpenSearch/pull/19802))
- Change the default value of doc_values in WildcardFieldMapper to true. ([#19796](https://github.com/opensearch-project/OpenSearch/pull/19796))
- Make Engine#loadHistoryUUID() protected and Origin#isFromTranslog() public ([#19753](https://github.com/opensearch-project/OpenSearch/pull/19752))
- Bump opensearch-protobufs dependency to 0.23.0 and update transport-grpc module compatibility ([#19831](https://github.com/opensearch-project/OpenSearch/pull/19831))
- Refactor the RefreshStats class to use the Builder pattern instead of constructors ([#19835](https://github.com/opensearch-project/OpenSearch/pull/19835))
- Refactor the DocStats and StoreStats class to use the Builder pattern instead of constructors ([#19863](https://github.com/opensearch-project/OpenSearch/pull/19863))
- Pass registry of headers from ActionPlugin.getRestHeaders to ActionPlugin.getRestHandlerWrapper ([#19875](https://github.com/opensearch-project/OpenSearch/pull/19875))
- Refactor the Condition.Stats and DirectoryFileTransferTracker.Stats class to use the Builder pattern instead of constructors ([#19862](https://github.com/opensearch-project/OpenSearch/pull/19862))
- Refactor the RemoteTranslogTransferTracker.Stats and RemoteSegmentTransferTracker.Stats class to use the Builder pattern instead of constructors ([#19837](https://github.com/opensearch-project/OpenSearch/pull/19837))
- Refactor the GetStats, FlushStats and QueryCacheStats class to use the Builder pattern instead of constructors ([#19935](https://github.com/opensearch-project/OpenSearch/pull/19935))
- Add RangeSemver for `dependencies` in `plugin-descriptor.properties` ([#19939](https://github.com/opensearch-project/OpenSearch/pull/19939))
- Refactor the FieldDataStats and CompletionStats class to use the Builder pattern instead of constructors ([#19936](https://github.com/opensearch-project/OpenSearch/pull/19936))
- Thread Context Preservation by gRPC Interceptor ([#19776](https://github.com/opensearch-project/OpenSearch/pull/19776))
- Update NoOpResult constructors in the Engine to be public ([#19950](https://github.com/opensearch-project/OpenSearch/pull/19950))
- Make XContentMapValues.filter case insensitive ([#19976](https://github.com/opensearch-project/OpenSearch/pull/19976))
- Refactor the TranslogStats and RequestCacheStats class to use the Builder pattern instead of constructors ([#19961](https://github.com/opensearch-project/OpenSearch/pull/19961))
- Improve performance of matrix_stats aggregation ([#19989](https://github.com/opensearch-project/OpenSearch/pull/19989))
- Refactor the IndexPressutreStats, DeviceStats and TransportStats class to use the Builder pattern instead of constructors ([#19991](https://github.com/opensearch-project/OpenSearch/pull/19991))
- Refactor the Cache.CacheStats class to use the Builder pattern instead of constructors ([#20015](https://github.com/opensearch-project/OpenSearch/pull/20015))
- Refactor the HttpStats, ScriptStats, AdaptiveSelectionStats and OsStats class to use the Builder pattern instead of constructors ([#20014](https://github.com/opensearch-project/OpenSearch/pull/20014))
- Bump opensearch-protobufs dependency to 0.24.0 and update transport-grpc module compatibility ([#20059](https://github.com/opensearch-project/OpenSearch/pull/20059))

- Refactor the ShardStats, WarmerStats and IndexingPressureStats class to use the Builder pattern instead of constructors ([#19966](https://github.com/opensearch-project/OpenSearch/pull/19966))
- Throw exceptions for currently unsupported GRPC request-side fields ([#20162](https://github.com/opensearch-project/OpenSearch/pull/20162))
- Support ignore_above for keyword/wildcard field and optimise text field under derived source ([#20113](https://github.com/opensearch-project/OpenSearch/pull/20113))

### Fixed
- Fix flaky test failures in ShardsLimitAllocationDeciderIT ([#20375](https://github.com/opensearch-project/OpenSearch/pull/20375))
- Prevent criteria update for context aware indices ([#20250](https://github.com/opensearch-project/OpenSearch/pull/20250))
- Update EncryptedBlobContainer to adhere limits while listing blobs in specific sort order if wrapped blob container supports ([#20514](https://github.com/opensearch-project/OpenSearch/pull/20514))
- [segment replication] Fix segment replication infinite retry due to stale metadata checkpoint ([#20551](https://github.com/opensearch-project/OpenSearch/pull/20551))
- Changing opensearch.cgroups.hierarchy.override causes java.lang.SecurityException exception ([#20565](https://github.com/opensearch-project/OpenSearch/pull/20565))
- Fix CriteriaBasedCodec to work with delegate codec. ([#20442](https://github.com/opensearch-project/OpenSearch/pull/20442))
- Fix WLM workload group creation failing due to updated_at clock skew ([#20486](https://github.com/opensearch-project/OpenSearch/pull/20486))
- Fix SLF4J component error ([#20587](https://github.com/opensearch-project/OpenSearch/pull/20587))
- Service does not start on Windows with OpenJDK ([#20615](https://github.com/opensearch-project/OpenSearch/pull/20615))
- Update RemoteClusterStateCleanupManager to performed batched deletions of stale ClusterMetadataManifests and address deletion timeout issues ([#20566](https://github.com/opensearch-project/OpenSearch/pull/20566))
- Fix the regression of terms agg optimization at high cardinality ([#20623](https://github.com/opensearch-project/OpenSearch/pull/20623))

### Dependencies
- Bump `ch.qos.logback:logback-core` and `ch.qos.logback:logback-classic` from 1.5.24 to 1.5.27 ([#20525](https://github.com/opensearch-project/OpenSearch/pull/20525))
- Bump `org.apache.commons:commons-text` from 1.14.0 to 1.15.0 ([#20576](https://github.com/opensearch-project/OpenSearch/pull/20576))
- Bump `aws-actions/configure-aws-credentials` from 5 to 6 ([#20577](https://github.com/opensearch-project/OpenSearch/pull/20577))
- Bump `netty` from 4.2.9.Final to 4.2.10.Final ([#20586](https://github.com/opensearch-project/OpenSearch/pull/20586))
- Bump `reactor-netty` from 1.3.2 to 1.3.3 ([#20589](https://github.com/opensearch-project/OpenSearch/pull/20589))
- Bump `reactor` from 3.8.2 to 3.8.3 ([#20589](https://github.com/opensearch-project/OpenSearch/pull/20589))
- Bump `tj-actions/changed-files` from 47.0.1 to 47.0.2 ([#20638](https://github.com/opensearch-project/OpenSearch/pull/20638))

### Removed

[Unreleased 3.x]: https://github.com/opensearch-project/OpenSearch/compare/3.6...main
