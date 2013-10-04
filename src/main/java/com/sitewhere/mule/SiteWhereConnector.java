/**
 * This file was automatically generated by the Mule Development Kit
 */
package com.sitewhere.mule;

import java.awt.Point;
import java.awt.Polygon;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.apache.log4j.Logger;
import org.mule.api.MuleContext;
import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.api.annotations.Configurable;
import org.mule.api.annotations.Connector;
import org.mule.api.annotations.Processor;
import org.mule.api.annotations.display.FriendlyName;
import org.mule.api.annotations.lifecycle.Start;
import org.mule.api.annotations.param.Default;
import org.mule.api.annotations.param.Optional;

import com.sitewhere.mule.connector.SiteWhereContextLogger;
import com.sitewhere.mule.emulator.EmulatorPayloadParserDelegate;
import com.sitewhere.rest.model.SiteWhereContext;
import com.sitewhere.rest.model.device.Device;
import com.sitewhere.rest.model.device.DeviceEventBatch;
import com.sitewhere.rest.model.device.DeviceEventBatchResponse;
import com.sitewhere.rest.model.device.Zone;
import com.sitewhere.rest.service.SiteWhereClient;
import com.sitewhere.rest.service.search.DeviceAssignmentSearchResults;
import com.sitewhere.rest.service.search.ZoneSearchResults;
import com.sitewhere.spi.ISiteWhereContext;
import com.sitewhere.spi.SiteWhereException;
import com.sitewhere.spi.common.ILocation;
import com.sitewhere.spi.device.IDeviceAlert;
import com.sitewhere.spi.device.IDeviceLocation;
import com.sitewhere.spi.mule.IMuleProperties;
import com.sitewhere.spi.mule.delegate.IOperationLifecycleDelegate;
import com.sitewhere.spi.mule.delegate.IPayloadParserDelegate;
import com.sitewhere.spi.mule.delegate.ISiteWhereDelegate;
import com.sitewhere.spi.mule.delegate.IZoneDelegate;

/**
 * Allows SiteWhere operations to be executed from within a Mule flow.
 * 
 * @author Derek Adams
 */
@Connector(name = "sitewhere", schemaVersion = "1.0", friendlyName = "SiteWhere")
public class SiteWhereConnector {

	/** Static logger instance */
	private static Logger LOGGER = Logger.getLogger(SiteWhereConnector.class);

	/** SiteWhere client */
	private SiteWhereClient client;

	/** Used to log SiteWhereContext to console */
	private SiteWhereContextLogger contextLogger = new SiteWhereContextLogger();

	/** Classloader that gets around Mule bugs */
	private SiteWhereClassloader swClassLoader;

	/**
	 * SiteWhere API URL.
	 */
	@Optional
	@Configurable
	@Default("http://localhost:8080/sitewhere/api/")
	@FriendlyName("SiteWhere API URL")
	private String apiUrl;

	/**
	 * Show extra debug information for SiteWhere components.
	 */
	@Optional
	@Configurable
	@Default("false")
	@FriendlyName("Enable SiteWhere Debugging")
	private Boolean debug = false;

	@Inject
	private MuleContext muleContext;

	@Start
	public void doStart() throws MuleException {
		client = new SiteWhereClient(getApiUrl());
		swClassLoader = new SiteWhereClassloader(muleContext);
		LOGGER.info("SiteWhere connector using base API url: " + getApiUrl());
	}

	/**
	 * Logs information about the current SiteWhere context to the console.
	 * 
	 * {@sample.xml ../../../doc/SiteWhere-connector.xml.sample sitewhere:context-logger}
	 * 
	 * @param event
	 *            injected Mule event
	 * @return the event after processing.
	 * @throws MuleException
	 *             should not be thrown
	 */
	@Inject
	@Processor()
	public MuleEvent contextLogger(MuleEvent event) throws MuleException {
		try {
			ISiteWhereContext context = getSiteWhereContext(event);
			try {
				contextLogger.showDebugOutput(context);
			} catch (Throwable e) {
				LOGGER.error("Unable to marshal SiteWhere context information.", e);
			}
			return event;
		} catch (SiteWhereException e) {
			LOGGER.error(e);
			return event;
		}
	}

	/**
	 * Locates a device by its unique hardware id.
	 * 
	 * {@sample.xml ../../../doc/SiteWhere-connector.xml.sample sitewhere:find-device-by-hardware-id}
	 * 
	 * @param hardwareId
	 *            unique hardware id of device to find
	 * @return the device if found
	 * @throws SiteWhereException
	 *             if the SiteWhere call fails
	 */
	@Inject
	@Processor
	public Device findDeviceByHardwareId(@FriendlyName("Hardware Id") String hardwareId)
			throws SiteWhereException {
		return client.getDeviceByHardwareId(hardwareId);
	}

	/**
	 * Update the current device assignment location with the latest location information in the request.
	 * 
	 * {@sample.xml ../../../doc/SiteWhere-connector.xml.sample sitewhere:update-assignment-location}
	 * 
	 * @param token
	 *            token to update or blank for current device assignment
	 * @param event
	 *            current Mule event
	 * @return the updated device assignment
	 * @throws SiteWhereException
	 *             if there is an error during update
	 */
	@Inject
	@Processor
	public ISiteWhereContext updateAssignmentLocation(
			@FriendlyName("Assignment Token") @Optional String token, MuleEvent event)
			throws SiteWhereException {
		ISiteWhereContext context = getSiteWhereContext(event);
		if ((token == null) || (token.trim().length() == 0)) {
			token = context.getDeviceAssignment().getToken();
		}
		if (context.getDeviceLocations().size() > 0) {
			IDeviceLocation latest = null;
			for (IDeviceLocation location : context.getDeviceLocations()) {
				if ((latest == null) || (location.getEventDate().after(latest.getEventDate()))) {
					latest = location;
				}
			}
			LOGGER.info("Updating device assignment location.");
			client.updateDeviceAssignmentLocation(token, latest.getId());
			return context;
		} else {
			LOGGER.info("No device locations available to update from.");
			return null;
		}
	}

	/**
	 * Save the device measurements currently in the SiteWhereContext.
	 * 
	 * {@sample.xml ../../../doc/SiteWhere-connector.xml.sample sitewhere:save-device-events}
	 * 
	 * @param delegate
	 *            executes custom logic before or after operation is processed
	 * @param event
	 *            mule event
	 * @return SiteWhere context
	 * @throws SiteWhereException
	 *             if save fails
	 */
	@Inject
	@Processor
	public ISiteWhereContext saveDeviceEvents(@FriendlyName("Lifecycle Delegate") @Optional String delegate,
			MuleEvent event) throws SiteWhereException {
		ISiteWhereContext context = getSiteWhereContext(event);
		IOperationLifecycleDelegate delegateInstance = null;
		if (delegate != null) {
			delegateInstance = createDelegate(delegate, IOperationLifecycleDelegate.class);
			delegateInstance.beforeOperation(context, client, event);
		}

		// Send unsaved events in a batch to be saved.
		DeviceEventBatch batch = new DeviceEventBatch();
		batch.getMeasurements().addAll(context.getUnsavedDeviceMeasurements());
		batch.getLocations().addAll(context.getUnsavedDeviceLocations());
		batch.getAlerts().addAll(context.getUnsavedDeviceAlerts());
		DeviceEventBatchResponse response = client.addDeviceEventBatch(context.getDevice().getHardwareId(),
				batch);

		// Clear out unsaved events and copy saved events from response.
		context.getUnsavedDeviceMeasurements().clear();
		context.getUnsavedDeviceLocations().clear();
		context.getUnsavedDeviceAlerts().clear();
		context.getDeviceMeasurements().addAll(response.getCreatedMeasurements());
		context.getDeviceLocations().addAll(response.getCreatedLocations());
		context.getDeviceAlerts().addAll(response.getCreatedAlerts());

		if (delegateInstance != null) {
			delegateInstance.afterOperation(context, client, event);
		}
		return context;
	}

	/**
	 * Get the history of device assignments for a given hardware id.
	 * 
	 * {@sample.xml ../../../doc/SiteWhere-connector.xml.sample sitewhere:get-device-assignment-history}
	 * 
	 * @param hardwareId
	 *            hardware id or blank to use id from device in SiteWhere context
	 * @param delegate
	 *            executes custom logic before or after operation is processed
	 * @param event
	 *            Mule event
	 * @return search results wrapping the device assignments
	 * @throws SiteWhereException
	 *             if there is an error processing the request
	 */
	@Inject
	@Processor
	public DeviceAssignmentSearchResults getDeviceAssignmentHistory(
			@FriendlyName("Hardware Id") @Optional String hardwareId,
			@FriendlyName("Lifecycle Delegate") @Optional String delegate, MuleEvent event)
			throws SiteWhereException {
		ISiteWhereContext context = getSiteWhereContext(event);
		IOperationLifecycleDelegate delegateInstance = null;
		if (delegate != null) {
			delegateInstance = createDelegate(delegate, IOperationLifecycleDelegate.class);
			delegateInstance.beforeOperation(context, client, event);
		}
		if (hardwareId == null) {
			hardwareId = context.getDevice().getHardwareId();
		}
		DeviceAssignmentSearchResults history = client.listDeviceAssignmentHistory(context.getDevice()
				.getHardwareId());
		event.getMessage().setPayload(history);
		if (delegateInstance != null) {
			delegateInstance.afterOperation(context, client, event);
		}
		return history;
	}

	/**
	 * Check whether locations are within zones specified for the site.
	 * 
	 * {@sample.xml ../../../doc/SiteWhere-connector.xml.sample sitewhere:perform-zone-checks}
	 * 
	 * @param delegate
	 *            delegate that generates alerts based on zones.
	 * @param event
	 *            mule event
	 * @return a list of alerts generated from the zone checks.
	 * @throws SiteWhereException
	 *             if processing fails
	 */
	@Inject
	@Processor
	public List<IDeviceAlert> performZoneChecks(@FriendlyName("Zone Delegate") String delegate,
			MuleEvent event) throws SiteWhereException {
		ISiteWhereContext context = getSiteWhereContext(event);
		IZoneDelegate delegateInstance = null;
		List<IDeviceAlert> results = new ArrayList<IDeviceAlert>();
		if (delegate != null) {
			delegateInstance = createDelegate(delegate, IZoneDelegate.class);
			ZoneSearchResults zones = client.listZonesForSite(context.getDeviceAssignment().getSiteToken());
			System.out.println("Zone processor found " + zones.getNumResults() + " zones.");
			for (Zone zone : zones.getResults()) {
				Polygon poly = new Polygon();
				for (ILocation location : zone.getCoordinates()) {
					int lat = (int) (location.getLatitude() * 100000);
					int lon = (int) (location.getLongitude() * 100000);
					poly.addPoint(lon, lat);
				}
				for (IDeviceLocation location : context.getDeviceLocations()) {
					int lat = (int) (location.getLatitude() * 100000);
					int lon = (int) (location.getLongitude() * 100000);
					boolean inside = poly.contains(new Point(lon, lat));
					IDeviceAlert alert = delegateInstance.handleZoneResults(context, zone, location, inside);
					if (alert != null) {
						results.add(alert);
					}
				}
			}
		}
		return results;
	}

	/**
	 * Executes a delegate class that has access to SiteWhere and Mule internals.
	 * 
	 * {@sample.xml ../../../doc/SiteWhere-connector.xml.sample sitewhere:sitewhere-delegate}
	 * 
	 * @param delegate
	 *            delegate class to invoke
	 * @param event
	 *            mule event
	 * @return the sitewhere context
	 * @throws SiteWhereException
	 *             if processing fails
	 */
	@Inject
	@Processor
	public ISiteWhereContext sitewhereDelegate(@FriendlyName("SiteWhere Delegate") String delegate,
			MuleEvent event) throws SiteWhereException {
		ISiteWhereContext context = getSiteWhereContext(event);
		ISiteWhereDelegate delegateInstance = null;
		if (delegate != null) {
			delegateInstance = createDelegate(delegate, ISiteWhereDelegate.class);
			delegateInstance.process(context, client, event);
		}
		return context;
	}

	/**
	 * Populates a new SiteWhere context from information in the current Mule event.
	 * 
	 * {@sample.xml ../../../doc/SiteWhere-connector.xml.sample sitewhere:payload-to-sitewhere-context}
	 * 
	 * @param delegate
	 *            delegate implementing <code>IPayloadParserDelegate</code>
	 * @param event
	 *            current Mule event
	 * @return the resulting SiteWhere context.
	 * @throws SiteWhereException
	 *             if an exception is thrown from the delegate.
	 */
	@Inject
	@Processor
	public ISiteWhereContext payloadToSitewhereContext(
			@FriendlyName("Payload Parser Delegate") String delegate, MuleEvent event)
			throws SiteWhereException {
		SiteWhereContext context = new SiteWhereContext();
		event.setFlowVariable(IMuleProperties.SITEWHERE_CONTEXT, context);

		IPayloadParserDelegate delegateInstance = null;
		if (delegate != null) {
			delegateInstance = createDelegate(delegate, IPayloadParserDelegate.class);
			delegateInstance.initialize(event);
			String hardwareId = delegateInstance.getDeviceHardwareId();
			if (hardwareId == null) {
				throw new SiteWhereException("Payload parser delegate returned null for hardware id.");
			}
			Device device = client.getDeviceByHardwareId(hardwareId);
			if (device == null) {
				throw new SiteWhereException("Device not found for hardware id: " + hardwareId);
			}
			context.setDevice(device);
			context.setDeviceAssignment(device.getAssignment());
			context.setUnsavedDeviceLocations(delegateInstance.getLocations());
			context.setUnsavedDeviceMeasurements(delegateInstance.getMeasurements());
			context.setUnsavedDeviceAlerts(delegateInstance.getAlerts());
			context.setReplyTo(delegateInstance.getReplyTo());
			return context;
		} else {
			throw new SiteWhereException("Payload parser delegate required but not specified.");
		}
	}

	/**
	 * Creates a SiteWhere context from the event payload with the assumption that the payload is a JSON
	 * string repesenting a {@link EmulatorDevice} object.
	 * 
	 * {@sample.xml ../../../doc/SiteWhere-connector.xml.sample sitewhere:emulator}
	 * 
	 * @param event
	 *            current Mule event
	 * @return a SiteWhere context built from the payload
	 * @throws SiteWhereException
	 *             if there is an error creating the context
	 */
	@Inject
	@Processor
	public ISiteWhereContext emulator(MuleEvent event) throws SiteWhereException {
		return payloadToSitewhereContext(EmulatorPayloadParserDelegate.class.getName(), event);
	}

	/**
	 * Get the SiteWhereContext from a pre-determined flow variable.
	 * 
	 * @param event
	 * @return
	 * @throws SiteWhereException
	 */
	protected ISiteWhereContext getSiteWhereContext(MuleEvent event) throws SiteWhereException {
		ISiteWhereContext context = (ISiteWhereContext) event
				.getFlowVariable(IMuleProperties.SITEWHERE_CONTEXT);
		if (context == null) {
			throw new SiteWhereException("SiteWhereContext not found in expected flow variable.");
		}
		return context;
	}

	@SuppressWarnings("unchecked")
	protected <T> T createDelegate(String classname, Class<T> classtype) throws SiteWhereException {
		try {
			Class<?> resolved = swClassLoader.loadClass(classname);
			if (!classtype.isAssignableFrom(resolved)) {
				throw new SiteWhereException("Delgate not an instance of " + classtype.getName());
			}
			Object created = resolved.newInstance();
			return (T) created;
		} catch (ClassNotFoundException e) {
			throw new SiteWhereException("Delegate class not found.", e);
		} catch (InstantiationException e) {
			throw new SiteWhereException("Could not create delegate class.", e);
		} catch (IllegalAccessException e) {
			throw new SiteWhereException("Could not access delegate class.", e);
		}
	}

	public String getApiUrl() {
		return apiUrl;
	}

	public void setApiUrl(String apiUrl) {
		this.apiUrl = apiUrl;
	}

	public Boolean getDebug() {
		return debug;
	}

	public void setDebug(Boolean debug) {
		this.debug = debug;
	}

	public MuleContext getMuleContext() {
		return muleContext;
	}

	public void setMuleContext(MuleContext muleContext) {
		this.muleContext = muleContext;
	}
}