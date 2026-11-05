//? if fabric && <1.21 {
/*package dev.advik.mcdt.platform.fabric;

import org.apache.logging.log4j.LogManager;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/^*
 * Sends SLF4J logging (MCDT's and its libraries') to Minecraft's Log4j. Minecraft ships SLF4J only since 1.18, so
 * older nodes bundle slf4j-api, and FabricEntrypoint selects this provider through the slf4j.provider property.
 ^/
public final class Log4jSlf4jProvider implements SLF4JServiceProvider {
	private final Map<String, Bridge> loggers = new ConcurrentHashMap<>();
	private final ILoggerFactory factory = name -> loggers.computeIfAbsent(name, Bridge::new);
	private final IMarkerFactory markers = new BasicMarkerFactory();
	private final MDCAdapter mdc = new NOPMDCAdapter();

	@Override
	public ILoggerFactory getLoggerFactory() {
		return factory;
	}

	@Override
	public IMarkerFactory getMarkerFactory() {
		return markers;
	}

	@Override
	public MDCAdapter getMDCAdapter() {
		return mdc;
	}

	@Override
	public String getRequestedApiVersion() {
		return "2.0.99";
	}

	@Override
	public void initialize() {
	}

	private static final class Bridge extends AbstractLogger {
		private final transient org.apache.logging.log4j.Logger target;

		Bridge(String name) {
			this.name = name;
			this.target = LogManager.getLogger(name);
		}

		private static org.apache.logging.log4j.Level level(Level level) {
			return switch (level) {
				case TRACE -> org.apache.logging.log4j.Level.TRACE;
				case DEBUG -> org.apache.logging.log4j.Level.DEBUG;
				case INFO -> org.apache.logging.log4j.Level.INFO;
				case WARN -> org.apache.logging.log4j.Level.WARN;
				case ERROR -> org.apache.logging.log4j.Level.ERROR;
			};
		}

		private boolean enabled(Level level) {
			return target.isEnabled(level(level));
		}

		@Override
		protected String getFullyQualifiedCallerName() {
			return null;
		}

		@Override
		protected void handleNormalizedLoggingCall(Level level, Marker marker, String pattern, Object[] arguments, Throwable throwable) {
			String message = MessageFormatter.basicArrayFormat(pattern, arguments);
			if (throwable != null) {
				target.log(level(level), message, throwable);
			} else {
				target.log(level(level), message);
			}
		}

		@Override public boolean isTraceEnabled() { return enabled(Level.TRACE); }
		@Override public boolean isTraceEnabled(Marker marker) { return enabled(Level.TRACE); }
		@Override public boolean isDebugEnabled() { return enabled(Level.DEBUG); }
		@Override public boolean isDebugEnabled(Marker marker) { return enabled(Level.DEBUG); }
		@Override public boolean isInfoEnabled() { return enabled(Level.INFO); }
		@Override public boolean isInfoEnabled(Marker marker) { return enabled(Level.INFO); }
		@Override public boolean isWarnEnabled() { return enabled(Level.WARN); }
		@Override public boolean isWarnEnabled(Marker marker) { return enabled(Level.WARN); }
		@Override public boolean isErrorEnabled() { return enabled(Level.ERROR); }
		@Override public boolean isErrorEnabled(Marker marker) { return enabled(Level.ERROR); }
	}
}
*///?}
