package mil.nga.giat.geowave.core.store.adapter.statistics;

import java.io.Closeable;
import java.io.Flushable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.log4j.Logger;

import mil.nga.giat.geowave.core.index.ByteArrayId;
import mil.nga.giat.geowave.core.store.callback.DeleteCallback;
import mil.nga.giat.geowave.core.store.callback.IngestCallback;
import mil.nga.giat.geowave.core.store.callback.ScanCallback;
import mil.nga.giat.geowave.core.store.entities.GeoWaveKeyValue;

/**
 *
 * This tool does not react to callbacks if the provided statistics store is
 * null or the provided data adapter does not implement
 * {@link DataStatisticsStore}.
 *
 * @param <T>
 *            Entry type
 */
public class StatsCompositionTool<T> implements
		IngestCallback<T, GeoWaveKeyValue>,
		ScanCallback<T, GeoWaveKeyValue>,
		DeleteCallback<T, GeoWaveKeyValue>,
		AutoCloseable,
		Closeable,
		Flushable
{
	private final static Logger LOGGER = Logger.getLogger(
			StatsCompositionTool.class);
	public static final int FLUSH_STATS_THRESHOLD = 16384;

	int updateCount = 0;
	DataStatisticsStore statisticsStore;
	List<DataStatisticsBuilder<T>> statisticsBuilders = null;
	final Object MUTEX = new Object();
	protected boolean skipFlush = false;

	public StatsCompositionTool() {
		statisticsStore = null;
	}

	public StatsCompositionTool(
			final StatisticsProvider<T> statisticsProvider ) {
		this.statisticsStore = null;
		this.init(
				statisticsProvider);
	}

	public StatsCompositionTool(
			final StatisticsProvider<T> statisticsProvider,
			final DataStatisticsStore statisticsStore ) {
		this.statisticsStore = statisticsStore;
		this.init(
				statisticsProvider);
	}

	private void init(
			final StatisticsProvider<T> statisticsProvider ) {
		final ByteArrayId[] statisticsIds = statisticsProvider.getSupportedStatisticsIds();
		statisticsBuilders = new ArrayList<DataStatisticsBuilder<T>>(
				statisticsIds.length);
		for (final ByteArrayId id : statisticsIds) {
			statisticsBuilders.add(
					new DataStatisticsBuilder<T>(
							statisticsProvider,
							id));
		}
		try {
			final Object v = System.getProperty(
					"StatsCompositionTool.skipFlush");
			skipFlush = ((v != null) && v.toString().equalsIgnoreCase(
					"true"));
		}
		catch (final Exception ex) {
			LOGGER.error(
					"Unable to determine property AccumuloIndexWriter.skipFlush",
					ex);
		}
	}

	@Override
	public void entryDeleted(
			final T entry,
			final GeoWaveKeyValue kv ) {
		if (statisticsBuilders == null) {
			return;
		}
		synchronized (MUTEX) {
			for (final DataStatisticsBuilder<T> builder : statisticsBuilders) {
				builder.entryDeleted(
						entry,
						kv);
			}
			updateCount++;
			checkStats();
		}

	}

	@Override
	public void entryScanned(
			final T entry,
			final GeoWaveKeyValue kv ) {
		if (statisticsBuilders == null) {
			return;
		}

		synchronized (MUTEX) {
			for (final DataStatisticsBuilder<T> builder : statisticsBuilders) {
				builder.entryScanned(
						entry,
						kv);
			}
			updateCount++;
			checkStats();
		}

	}

	/**
	 * Update statistics store
	 */
	@Override
	public void flush() {
		if (statisticsBuilders == null) {
			return;
		}

		synchronized (MUTEX) {
			for (final DataStatisticsBuilder<T> builder : statisticsBuilders) {
				final Collection<DataStatistics<T>> statistics = builder.getStatistics();
				for (final DataStatistics<T> s : statistics) {
					statisticsStore.incorporateStatistics(
							s);
				}
				statistics.clear();
			}
		}
	}

	/**
	 * Reset statistics, losing and updates since last flush
	 */
	public void reset() {
		if (statisticsBuilders == null) {
			return;
		}

		synchronized (MUTEX) {
			for (final DataStatisticsBuilder<T> builder : statisticsBuilders) {
				final Collection<DataStatistics<T>> statistics = builder.getStatistics();
				statistics.clear();
			}
		}
	}

	@Override
	public void entryIngested(
			final T entry,
			final GeoWaveKeyValue... kvs ) {
		if (statisticsBuilders == null) {
			return;
		}

		synchronized (MUTEX) {
			for (final DataStatisticsBuilder<T> builder : statisticsBuilders) {
				builder.entryIngested(
						entry,
						kvs);
			}
			updateCount++;
			checkStats();
		}
	}

	@Override
	public void close() {
		flush();
	}

	public void setStatisticsStore(
			final DataStatisticsStore statisticsStore ) {
		this.statisticsStore = statisticsStore;
	}

	private void checkStats() {
		if (!skipFlush && (updateCount > FLUSH_STATS_THRESHOLD)) {
			updateCount = 0;
		}
	}

}
