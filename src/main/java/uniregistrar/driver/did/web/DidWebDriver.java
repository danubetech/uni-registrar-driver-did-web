package uniregistrar.driver.did.web;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import foundation.identity.did.DIDDocument;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uniregistrar.RegistrationException;
import uniregistrar.driver.AbstractDriver;
import uniregistrar.request.CreateRequest;
import uniregistrar.request.DeactivateRequest;
import uniregistrar.request.UpdateRequest;
import uniregistrar.state.CreateState;
import uniregistrar.state.DeactivateState;
import uniregistrar.state.SetCreateStateFinished;
import uniregistrar.state.UpdateState;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class DidWebDriver extends AbstractDriver {

	public static final String METHOD_PREFIX = "did:web:";
	public static final String FILE_NAME = "/did.json";
	private static final Logger log = LogManager.getLogger(DidWebDriver.class);
	private URL baseUrl;
	private Path basePath;
	private Map<String, Object> properties;

	public DidWebDriver() {
		this(getPropertiesFromEnvironment());
	}

	public DidWebDriver(Map<String, Object> properties) {
		setProperties(properties);
		if (baseUrl == null) {
			throw new IllegalArgumentException("Base URL is not defined!");
		}
		if (!"https".equals(baseUrl.getProtocol())) {
			throw new IllegalArgumentException("Protocol must be https. Provided URL protocol is " + baseUrl.getProtocol());
		}
		if (basePath == null) {
			throw new IllegalArgumentException("Base path is not defined!");
		}
		if (!Files.isDirectory(basePath)) {
			throw new IllegalArgumentException("Base path is not a directory!");
		}
		if (!Files.isWritable(basePath)) {
			throw new IllegalArgumentException("Base path is not writable!");
		}
	}

	private static Map<String, Object> getPropertiesFromEnvironment() {

		log.debug("Loading from environment: {}", System::getenv);

		Map<String, Object> properties = new HashMap<>();

		try {

			String env_baseUrl = System.getenv("uniregistrar_driver_did_web_baseUrl");
			String env_basePath = System.getenv("uniregistrar_driver_did_web_basePath");

			if (!Strings.isNullOrEmpty(env_baseUrl)) properties.put("baseUrl", env_baseUrl);
			if (!Strings.isNullOrEmpty(env_basePath)) properties.put("basePath", env_basePath);
		} catch (Exception ex) {

			throw new IllegalArgumentException(ex.getMessage(), ex);
		}

		return properties;

	}

	public static Path getDidPath(String id, int offset) {
		return Paths.get(id.substring(offset));

	}

	@Override
	public CreateState create(CreateRequest request) throws RegistrationException {
		Preconditions.checkNotNull(request);
		DIDDocument document = request.getDidDocument();

		if (document == null) throw new RegistrationException("DID Doc is null!");
		boolean idExists = document.getId() != null;
		UUID id = null;

		Path didPath = idExists ? validateAndGetPath(document.getId().toString()) : generateNewPath(id = UUID.randomUUID());
		if (Files.exists(didPath)) throw new RegistrationException("DID is already exists!");

		String did = null;

		if (!idExists) {
			did = METHOD_PREFIX + baseUrl.getHost() + ":" + id;
			document.getJsonObject().put("id", URI.create(did));
		}

		try {
			storeDidDocument(didPath, document);
		} catch (IOException e) {
			throw new RegistrationException(e.getMessage());
		}


		Map<String, Object> result = new HashMap<>();
		result.put("didDocument", document);

		CreateState registerState = CreateState.build();
		registerState.setDidState(result);

		SetCreateStateFinished.setStateFinished(registerState, idExists ? document.getId().toString() : did, null);

		return registerState;
	}

	@Override
	public UpdateState update(UpdateRequest request) throws RegistrationException {
		Preconditions.checkNotNull(request);
		DIDDocument document = request.getDidDocument();

		if (document == null) throw new RegistrationException("DID Doc is null!");
		if (document.getId() == null) throw new RegistrationException("DID is null");

		Path didPath = validateAndGetPath(document.getId().toString());
		if (!Files.exists(didPath)) throw new RegistrationException("DID does not exists!");

		Path didDocFile = Paths.get(didPath.toString());

		try {
			Files.delete(Paths.get(didDocFile.toString(), FILE_NAME));
			storeDidDocument(didDocFile, document);
		} catch (IOException e) {
			throw new RegistrationException(e.getMessage());
		}

		UpdateState updateState = UpdateState.build();
		Map<String, Object> result = new HashMap<>();
		result.put("state", "finished");
		result.put("didDocument", document);
		updateState.setDidState(result);

		return updateState;
	}

	@Override
	public DeactivateState deactivate(DeactivateRequest request) throws RegistrationException {
		Preconditions.checkNotNull(request);
		if (request.getIdentifier() == null) throw new RegistrationException("Identifier is null!");

		Path didPath = validateAndGetPath(request.getIdentifier());
		if (!Files.exists(didPath)) throw new RegistrationException("DID does not exists!");

		Path didDocFile = Paths.get(didPath.toString(), FILE_NAME);

		try {
			Files.delete(didDocFile);
		} catch (IOException e) {
			throw new RegistrationException(e.getMessage());
		}


		DeactivateState deactivateState = DeactivateState.build();
		deactivateState.getDidState().put("state", "finished");

		return deactivateState;

	}

	@Override
	public Map<String, Object> properties() throws RegistrationException {
		return properties;
	}

	public Path validateAndGetPath(String did) throws RegistrationException {
		if (did == null) throw new RegistrationException("DID is null!");
		if (!did.startsWith(METHOD_PREFIX)) throw new RegistrationException("Unknown did method");


		String[] parsed = did.substring(DidWebDriver.METHOD_PREFIX.length()).split(":");
		if (parsed.length < 2) throw new RegistrationException("DID Format error!");
		if (!baseUrl.getHost().equalsIgnoreCase(parsed[0])) throw new RegistrationException("Domain name mismatch!");

		return Paths.get(basePath.toString(), Arrays.stream(parsed)
													.skip(1)
													.map(x -> x + "/")
													.reduce("/", String::concat));

	}

	private Path generateNewPath(UUID id) {
		return Paths.get(basePath.toString(), id.toString());
	}

	public static void storeDidDocument(Path filePath, DIDDocument document) throws IOException {

		Files.createDirectories(filePath);

		try (Writer fileWriter = new OutputStreamWriter(new FileOutputStream(filePath + FILE_NAME), StandardCharsets.UTF_8)) {
			fileWriter.write(document.toJson());
			fileWriter.flush();
		}
	}

	public Map<String, Object> getProperties() {
		return properties;
	}


	public final void setProperties(Map<String, Object> properties) {
		this.properties = properties;
		configureFromProperties();
	}

	private void configureFromProperties() {

		log.debug("Configuring from properties: {}", this::getProperties);
		try {
			String prop_baseUrl = (String) properties.get("baseUrl");
			String prop_basePath = (String) properties.get("basePath");

			if (!Strings.isNullOrEmpty(prop_baseUrl)) this.baseUrl = new URL(prop_baseUrl);
			if (!Strings.isNullOrEmpty(prop_basePath)) this.basePath = Paths.get(prop_basePath);
		} catch (Exception ex) {
			throw new IllegalArgumentException(ex.getMessage(), ex);
		}
	}
}
