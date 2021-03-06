package demo.springreactive.consumerapp.unit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import demo.springreactive.consumerapp.config.PatientRegistryConfiguration;
import demo.springreactive.consumerapp.model.PatientDTO;
import demo.springreactive.consumerapp.model.patientregistry.ClinicalDocument;
import demo.springreactive.consumerapp.model.patientregistry.ContactInfo;
import demo.springreactive.consumerapp.model.patientregistry.Patient;
import demo.springreactive.consumerapp.service.ReactivePatientConsumerService;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@ExtendWith(SpringExtension.class)
@Import({ReactivePatientConsumerService.class, PatientRegistryConfiguration.class})
@PropertySource({"classpath:application.properties", "classpath:application-test.properties"})
@EnableConfigurationProperties
class ReactivePatientConsumerServiceUTests {

	private static final int PATIENT_NUM = 5;
	private static final int DOCS_PER_PATIENT = 5;
	private WireMockServer wireMockServer;

	@Autowired
	private ReactivePatientConsumerService service;

	@Autowired
	private PatientRegistryConfiguration config;

	@BeforeEach
	public void init() {
		this.wireMockServer = new WireMockServer(this.config.getPort());
		this.wireMockServer.start();

		this.stubPatientList();
		this.stubDocumentList();
		this.stubContacts();
	}

	@AfterEach
	public void freeEnv() {
		this.wireMockServer.stop();
	}

	@Test
	void givenAListOfPatient_whenGetAllPatients_thenExpectAllPatientsReturned() {
		List<Patient> sourcePatientList = this.buildPatientsList(PATIENT_NUM);
		List<PatientDTO> patients = this.service.getAllPatients().collectList().block();

		assertThat(patients).isNotNull();
		assertThat(patients.size()).isEqualTo(PATIENT_NUM);

		for (int i = 0; i < PATIENT_NUM; i++) {
			PatientDTO expectedPatient = new PatientDTO(sourcePatientList.get(i));
			Optional<PatientDTO> optPatient = patients
					.stream()
					.filter(p -> p.getFullName().equals(expectedPatient.getFullName()))
					.findAny();
			assertThat(optPatient).isPresent();
		}
	}

	@Test
	void testWithStepVerifier_checkCorrectListSize() {
		Flux<PatientDTO> patients = this.service.getAllPatients();

		StepVerifier
				.create(patients)
				.expectNextCount(PATIENT_NUM)
				.verifyComplete();
	}

	@Test
	void testWithStepVerifier_checkCorrectListElements() {
		Flux<PatientDTO> patients = this.service.getAllPatients();

		StepVerifier
				.create(patients)
				.expectNextMatches(p -> this.validatePatientById(p, "PAT_0"))
				.expectNextMatches(p -> this.validatePatientById(p, "PAT_1"))
				.expectNextMatches(p -> this.validatePatientById(p, "PAT_2"))
				.expectNextMatches(p -> this.validatePatientById(p, "PAT_3"))
				.expectNextMatches(p -> this.validatePatientById(p, "PAT_4"))
				.expectComplete()
				.verify();
	}

	private boolean validatePatientById(PatientDTO patient, String patientId) {
		String firstNameSuffix = patient.getFirstName().split("-")[1];
		String lastNameSuffix = patient.getLastName().split("-")[1];
		boolean validPatientName = firstNameSuffix.equals(patientId) && lastNameSuffix.equals(patientId);

		AtomicBoolean validPatientDocs = new AtomicBoolean(patient.getClinicalDocuments().size() == DOCS_PER_PATIENT);
		patient.getClinicalDocuments().forEach(d -> {
			String docTitleSuffix = d.getDocumentTitle().split("-")[1].trim();
			validPatientDocs.set(validPatientDocs.get() && docTitleSuffix.equals(patientId));
		});

		boolean validPatientContacts = patient.getContacts().getEmail().contains(patientId);

		return validPatientName && validPatientDocs.get() && validPatientContacts;
	}

	private void stubPatientList() {
		List<Patient> patientList = this.buildPatientsList(PATIENT_NUM);
		this.wireMockServer.stubFor(
				get(urlEqualTo(this.config.getEndpoint().getAllPatients()))
						.willReturn(aResponse()
								.withStatus(HttpStatus.OK.value())
								.withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
								.withBody(this.toResponseBody(patientList))
						)
		);
	}

	private void stubDocumentList() {
		List<Patient> patientsList = this.buildPatientsList(PATIENT_NUM);
		patientsList.forEach(patient -> {
			List<ClinicalDocument> clinicalDocuments = this.buildClinicalDocumentsList(patient.getId(), DOCS_PER_PATIENT);
			this.wireMockServer.stubFor(
					get(urlEqualTo(this.buildPatientClinicalDocsEndpoint(patient.getId())))
							.willReturn(aResponse()
									.withStatus(HttpStatus.OK.value())
									.withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
									.withBody(this.toResponseBody(clinicalDocuments))
							)
			);
		});
	}

	private void stubContacts() {
		List<Patient> patientsList = this.buildPatientsList(PATIENT_NUM);
		patientsList.forEach(patient -> {
			ContactInfo contactInfo = this.buildContactInfo(patient);
			this.wireMockServer.stubFor(
					get(urlEqualTo(this.buildPatientContactsInfoEndpoint(patient.getId())))
							.willReturn(aResponse()
									.withStatus(HttpStatus.OK.value())
									.withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
									.withBody(this.toResponseBody(contactInfo))
							)
			);
		});
	}

	private List<Patient> buildPatientsList(int patientsNum) {
		List<Patient> patients = new ArrayList<>();
		for (int i = 0; i < patientsNum; i++) {
			patients.add(new Patient("PAT_" + i, "FirstName-PAT_" + i, "LastName-PAT_" + i));
		}
		return patients;
	}

	private List<ClinicalDocument> buildClinicalDocumentsList(String patientId, int docsNum) {
		List<ClinicalDocument> docs = new ArrayList<>();
		for (int docId = 0; docId < docsNum; docId++) {
			docs.add(new ClinicalDocument(
					"DOC_" + docId,
					patientId,
					"Title - " + patientId,
					"Lorem ipsum"
			));
		}
		return docs;
	}

	private ContactInfo buildContactInfo(Patient patient) {
		return new ContactInfo(
				UUID.randomUUID().toString(),
				patient.getFirstName() + patient.getLastName() + "@email.com",
				patient.getId(),
				"+39 000 000 0000",
				"Dummy postal address, 5"
		);
	}

	private String buildPatientClinicalDocsEndpoint(String patientId) {
		return this.config.getEndpoint().getAllPatientDocuments().replace("{patient-id}", patientId);
	}

	private String buildPatientContactsInfoEndpoint(String patientId) {
		return this.config.getEndpoint().getAllPatientContacts().replace("{patient-id}", patientId);
	}

	@SneakyThrows
	private String toResponseBody(Object obj) {
		ObjectMapper mapper = new ObjectMapper();
		return mapper.writeValueAsString(obj);
	}
}
