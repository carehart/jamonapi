package com.jamonapi.log4j;

import com.jamonapi.*;
import com.jamonapi.utils.DefaultGeneralizer;
import com.jamonapi.utils.Generalizer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.Serializable;

/**
 * Implementaton of a log4j Appender that allows you to summarize log4j stats via jamon and view
 * the tail of the log in realtime in a jamon web page.  Click here for more info on how to use the <a href="http://jamonapi.sourceforge.net/log4j_jamonappender.html">JAMonAppender</a>.
 *
 * @since 2.82
 */

@Plugin(name = "JamonAppender", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE, printObject = true)
public class JAMonAppender extends AbstractAppender {
    /* Prefix for this classes jamon monitor labels */
    private static final String PREFIX = "com.jamonapi.log4j.JAMonAppender.";
    private static final String TOTAL_KEY = PREFIX + "TOTAL";

    // any of these poperties can be overridden via log4j configurators or by passing in the attribute in xml, json, properties, yaml format
    private int bufferSize = 100;

    private String units = "log4j"; // units in jamon monitors

    // indicates whether or not log4j LogEvent info is placed in jamon listener buffer.
    // This could potentially be slower though I didn't test it, and I
    // wouldn't be overly concerned about it. Note the LogEvent isn't converted to an
    // Object[] unless it is diplayed.
    private boolean enableListenerDetails = true;

    // Enable monitoring of the various log4j levels in jamon.  For example counts
    // and pass a key to TOTAL, ERROR, INFO ...
    private boolean enableLevelMonitoring = true;

    private boolean generalize = false;

    private Generalizer generalizer = new DefaultGeneralizer();

    static {
        // Register this object to be available for use in the JAMonListenerFactory.
        JAMonListenerFactory.put(new Log4jBufferListener());
    }

    /**
     * This constructor is called automatically by log4j/sl4j
     *
     * @param name
     * @param filter
     * @param layout
     * @param ignoreExceptions
     * @param bufferSize
     * @param enableListenerDetails
     * @param enableLevelMonitoring
     * @param generalize
     * @param levelListenerType
     */
    public JAMonAppender(final String name, final Filter filter, final Layout<? extends Serializable> layout, final boolean ignoreExceptions,
                         int bufferSize,
                         boolean enableListenerDetails,
                         boolean enableLevelMonitoring,
                         boolean generalize,
                         String levelListenerType) {
        super(name, filter, layout, ignoreExceptions, Property.EMPTY_ARRAY);
        setListenerBufferSize(bufferSize);
        setEnableListenerDetails(enableListenerDetails);
        setEnableLevelMonitoring(enableLevelMonitoring);
        setGeneralize(generalize);
        setEnableListeners(levelListenerType);
    }

    /**
     * Called automatically by log4j/sl4j. If values aren't provided defaults are used.
     *
     * @param name
     * @param ignoreExceptions
     * @param layout
     * @param filter
     * @param bufferSize
     * @param enableListenerDetails
     * @param enableLevelMonitoring
     * @param generalize
     * @param levelListenerType
     * @return
     */

    @PluginFactory
    public static JAMonAppender createAppender(@PluginAttribute("name") String name,
                                               @PluginAttribute("ignoreExceptions") boolean ignoreExceptions, // true - just log exception, false - log and pass exception to the application
                                               @PluginElement("Layout") Layout layout,
                                               @PluginElement("Filters") Filter filter,
                                               @PluginAttribute(value = "bufferSize", defaultInt = 100) int bufferSize,
                                               @PluginAttribute(value = "enableListenerDetails", defaultBoolean = true) boolean enableListenerDetails,
                                               @PluginAttribute(value = "enableLevelMonitoring", defaultBoolean = true) boolean enableLevelMonitoring,
                                               @PluginAttribute(value = "generalize", defaultBoolean = false) boolean generalize,
                                               @PluginAttribute(value = "enableListeners", defaultString = "NONE") String levelListenerType
    ) {
        if (layout == null) {
            layout = PatternLayout.createDefaultLayout();
        }

        return new JAMonAppender(name, filter, layout, ignoreExceptions, bufferSize, enableListenerDetails, enableLevelMonitoring, generalize, levelListenerType);
    }
    /**
     * If the appender is enabled then start and stop a JAMon entry. Depending
     * on how this object is configured it may also put details into a
     * JAMonBufferLister and generalize the logging message
     * (logger.error(message) etc) and put it in jamon too. By default it will
     * only do jamon records for each of the log4j Levels.
     * 
     * @param event
     */
    @Override
    public void append(LogEvent event) {
        if (MonitorFactory.isEnabled() && (getEnableLevelMonitoring() || getGeneralize())) {
            // might or might not make copy. in tests it didn't copy and did return the same instance.
            LogEvent immutableEvent = event.toImmutable();
            String message = (event.getMessage()==null) ? "" : event.getMessage().getFormattedMessage();;

            if (getEnableLevelMonitoring()) { // i.e. count at least one of TOTAL, ERROR, INFO etc.
                // monitor that counts all calls to log4j logging methods
                MonitorFactory.add(createKey(TOTAL_KEY, message, immutableEvent), 1);
                // monitor that counts calls to log4j at each level (DEBUG/WARN/...)
                MonitorFactory.add(createKey(getLevelKey(event), message, immutableEvent), 1);
            }

            // if the object was configured to generalize the message then do as
            // such. This will create a jamon record with the generalized method
            // so it is important for the developer to ensure that the generalized
            // message is unique enough not to grow jamon unbounded.
            if (getGeneralize()) {
                MonitorFactory.add(createKey(generalize(message), message, immutableEvent), 1);
            }
        }
    }

    // Return a key that will put LogEvent info in a bufferlistenr if
    // enableListenerDetails has been enabled,  else simply use the standard jamon MonKeyImp
    private MonKey createKey(String summaryLabel, String detailLabel, LogEvent event) {
        if (enableListenerDetails) // put array in details buffer
            return new Log4jMonKey(summaryLabel, detailLabel, units, event);
        else
            return new MonKeyImp(summaryLabel, detailLabel, units);

    }

    private String getLevelKey(LogEvent event) {
        return new StringBuilder(PREFIX).append(event.getLevel()).toString();
    }


    /**
     * @return Returns the units. By default this is 'log4j' though it can be
     *         changed. This is used as part of the jamon key.
     */
    public String getUnits() {
        return units;
    }

    /**
     * @param units
     *            The units to set.
     */
    public void setUnits(String units) {
        this.units = units;
    }

    /**
     * Specifies whether or not LogEvent info will be used in the attached
     * Log4jBufferListener. By default this is enabled.
     */
    public boolean getEnableListenerDetails() {
        return enableListenerDetails;
    }

    /**
     * Specify whether or not LogEvent info will be used in the attached
     * Log4jBufferListener
     */
    public void setEnableListenerDetails(boolean arrayDetails) {
        this.enableListenerDetails = arrayDetails;
    }

    /**
     * Specifies whether or not there will be a JAMon record for each log4j
     * Level (DEBUG/WARN/...), and another one that corresponds to all calls to
     * log4j logging methods. It is identified by the label TOTAL. By default
     * this is enabled.
     */
    public void setEnableLevelMonitoring(boolean enableLevelMonitoring) {
        this.enableLevelMonitoring = enableLevelMonitoring;

    }

    /** Returns whether or not LevelMonitoring is enabled or not. */
    public boolean getEnableLevelMonitoring() {
        return enableLevelMonitoring;
    }

    /**
     * Note this is primarily used by the log4j configurator. Valid values are
     * the various log4j levels:
     * 
     * <ul>
     * <li>TRACE/DEBUG/ERROR/WARN/INFO/ERROR, as well as...
     * <li>TOTAL (A listener that gets called for all levels),
     * <li>BASIC (same as calling TOTAL/ERROR/FATAL),
     * <li>ALL (same as calling TRACE/DEBUG/ERROR/WARN/INFO/ERROR/FATAL/TOTAL).
     * <li>NONE (Nothing will be tracked).
     * </ul>
     * 
     * <p>Note: Values are not case sensitive.
     * 
     * @param level
     */
    public void setEnableListeners(String level) {
        if (Level.DEBUG.toString().equalsIgnoreCase(level.toUpperCase()))
            addDefaultListener(MonitorFactory.getMonitor(PREFIX + Level.DEBUG, units));
        else if (Level.TRACE.toString().equalsIgnoreCase(level.toUpperCase()))
            addDefaultListener(MonitorFactory.getMonitor(PREFIX + Level.TRACE, units));
        else if (Level.INFO.toString().equalsIgnoreCase(level.toUpperCase()))
            addDefaultListener(MonitorFactory.getMonitor(PREFIX + Level.INFO, units));
        else if (Level.WARN.toString().equalsIgnoreCase(level.toUpperCase()))
            addDefaultListener(MonitorFactory.getMonitor(PREFIX + Level.WARN, units));
        else if (Level.ERROR.toString().equalsIgnoreCase(level.toUpperCase()))
            addDefaultListener(MonitorFactory.getMonitor(PREFIX + Level.ERROR, units));
        else if (Level.FATAL.toString().equalsIgnoreCase(level.toUpperCase()))
            addDefaultListener(MonitorFactory.getMonitor(PREFIX + Level.FATAL, units));
        else if ("TOTAL".equalsIgnoreCase(level.toUpperCase()))
            addDefaultListener(MonitorFactory.getMonitor(TOTAL_KEY, units));
        else if (Level.ALL.toString().equalsIgnoreCase(level.toUpperCase())) {
            addDefaultListener(MonitorFactory.getMonitor(PREFIX + Level.TRACE, units));
            addDefaultListener(MonitorFactory.getMonitor(PREFIX + Level.DEBUG, units));
            addDefaultListener(MonitorFactory.getMonitor(PREFIX + Level.INFO, units));
            addDefaultListener(MonitorFactory.getMonitor(PREFIX + Level.WARN, units));
            addDefaultListener(MonitorFactory.getMonitor(PREFIX + Level.ERROR, units));
            addDefaultListener(MonitorFactory.getMonitor(PREFIX + Level.FATAL, units));
            addDefaultListener(MonitorFactory.getMonitor(TOTAL_KEY, units));
        } else if ("BASIC".equalsIgnoreCase(level.toUpperCase())) {
            addDefaultListener(MonitorFactory.getMonitor(TOTAL_KEY, units));
            addDefaultListener(MonitorFactory.getMonitor(PREFIX + Level.ERROR, units));
            addDefaultListener(MonitorFactory.getMonitor(PREFIX + Level.FATAL, units));
        }
    }

    // Add a Log4jBufferListener to the passed in Monitor
    private void addDefaultListener(Monitor mon) {
        if (!mon.hasListeners()) {
            Log4jBufferListener listener = new Log4jBufferListener();
            listener.getBufferList().setBufferSize(bufferSize);
            mon.addListener("value", listener);
        }
    }

    /**
     * For defaultBufferSize to take hold it must be called before the first
     * call to setDefaultListeners. By default the buffer size is 100.
     * 
     * @param bufferSize
     */
    public void setListenerBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public int getListenerBufferSize() {
        return bufferSize;
    }

    /** Indicate whether or not a jamon record should be created from the passed in message.
     * Note you can use the DefaultGeneralizer, your own Generalizer.  It is very important that
     * you ensure the String returned by the generalizer is unique enough that JAMon doesn't grow unbounded.
     * For example by choosing to use no Generalizer you must pass in a relatively unique log4j string.
     * @param generalize
     */
    public void setGeneralize(boolean generalize) {
        this.generalize = generalize;
    }

    /** Return whether or not generalization will occur */
    public boolean getGeneralize() {
        return generalize;
    }

    /** generalize the passed in String if a Genaralizer is set */
    protected String generalize(String detailedMessage) {
        return (generalizer != null) ? generalizer.generalize(detailedMessage) : detailedMessage;
    }

    /** Enable the use of the DefaultGeneralizer. As a side effect setGeneralize(true) is called
     * telling this class to generalize.
     * @param enableDefaultGeneralizer
     */
    public void setEnableDefaultGeneralizer(boolean enableDefaultGeneralizer) {
        if (enableDefaultGeneralizer) {
            this.generalizer = new DefaultGeneralizer();
            setGeneralize(true);
        } else
            this.generalizer = null;
    }

    /** Indicates whether or not a Generalizer has been set */
    public boolean hasGeneralizer() {
        return (generalizer != null);
    }

    /**
     * Default generalizer based on com.jamonapi.utils.SQLDeArger. It
     * generalizes by replacing numbers and strings in single or double quotes
     * with '?'. i.e. select * from table where name = 'steve' and id=50 becomes
     * select * from table where name = ? and id=?. Developers can provide their
     * own Generalizer if this is not the desired behaviour. Although the
     * example uses a query the code works equally well with any String. The
     * generalizer is used to create a record appropriate for jamon from a
     * detail String that goes to log4j.
     */
    public void setGeneralizerClass(Generalizer generalizer) {
        this.generalizer = generalizer;
    }

    /** Pass in a string class name and this generalizer will be constructed an used.  For example com.jamonapi.utils.DefaultGeneralizer could be passed in
     * 
     * @param generalizerClassStr
     * @throws InstantiationException
     * @throws IllegalAccessException
     * @throws ClassNotFoundException
     */
    public void setGeneralizerDynamic(String generalizerClassStr) throws InstantiationException,
    IllegalAccessException, ClassNotFoundException {
        this.generalizer = (Generalizer) Class.forName(generalizerClassStr).newInstance();
    }

}
