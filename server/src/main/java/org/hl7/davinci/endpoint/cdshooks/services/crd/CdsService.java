package org.hl7.davinci.endpoint.cdshooks.services.crd;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Date;
import javax.validation.Valid;

import org.apache.commons.lang3.StringUtils;
import org.cdshooks.*;
import org.hl7.davinci.FhirComponentsT;
import org.hl7.davinci.PrefetchTemplateElement;
import org.hl7.davinci.RequestIncompleteException;
import org.hl7.davinci.endpoint.config.YamlConfig;
import org.hl7.davinci.endpoint.components.CardBuilder;
import org.hl7.davinci.endpoint.components.PrefetchHydrator;
import org.hl7.davinci.endpoint.components.CardBuilder.CqlResultsForCard;
import org.hl7.davinci.endpoint.components.QueryBatchRequest;
import org.hl7.davinci.endpoint.database.FhirResourceRepository;
import org.hl7.davinci.endpoint.database.RequestLog;
import org.hl7.davinci.endpoint.database.RequestService;
import org.hl7.davinci.endpoint.files.FileStore;
import org.hl7.davinci.endpoint.rules.CoverageRequirementRuleCriteria;
import org.hl7.davinci.endpoint.rules.CoverageRequirementRuleResult;
import org.hl7.davinci.r4.CardTypes;
import org.hl7.davinci.r4.CoverageGuidance;
import org.hl7.davinci.r4.crdhook.orderselect.OrderSelectRequest;
import org.hl7.davinci.r4.crdhook.ordersign.OrderSignRequest;
import org.hl7.fhir.r4.model.Bundle;
import org.json.simple.JSONArray;
import org.hl7.davinci.r4.crdhook.DiscoveryExtension;
import org.opencds.cqf.cql.engine.execution.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;
import org.json.simple.JSONObject;

@Component
public abstract class CdsService<requestTypeT extends CdsRequest<?, ?>> {
  static final Logger logger = LoggerFactory.getLogger(CdsService.class);

  /**
   * The {id} portion of the URL to this service which is available at
   * {baseUrl}/cds-services/{id}. REQUIRED
   */
  public String id;

  /**
   * The hook this service should be invoked on. REQUIRED
   */
  public Hook hook;

  /**
   * The human-friendly name of this service. RECOMMENDED
   */
  public String title;

  /**
   * The description of this service. REQUIRED
   */
  public String description;

  /**
   * An object containing key/value pairs of FHIR queries that this service is
   * requesting that the EHR prefetch and provide on each service call. The key is
   * a string that describes the type of data being requested and the value is a
   * string representing the FHIR query. OPTIONAL
   */
  public Prefetch prefetch;

  @Autowired
  private YamlConfig myConfig;

  @Autowired
  RequestService requestService;

  @Autowired
  FileStore fileStore;

  @Autowired
  private FhirResourceRepository fhirResourceRepository;

  private final List<PrefetchTemplateElement> prefetchElements;

  protected FhirComponentsT fhirComponents;

  private final DiscoveryExtension extension;

  /**
   * Create a new cdsservice.
   *
   * @param id               Will be used in the url, should be unique.
   * @param hook             Which hook can call this.
   * @param title            Human title.
   * @param description      Human description.
   * @param prefetchElements List of prefetch elements, will be in prefetch
   *                         template.
   * @param fhirComponents   Fhir components to use
   * @param extension        Custom CDS Hooks extensions.
   */
  public CdsService(String id, Hook hook, String title, String description,
      List<PrefetchTemplateElement> prefetchElements, FhirComponentsT fhirComponents,
      DiscoveryExtension extension) {

    if (id == null) {
      throw new NullPointerException("CDSService id cannot be null");
    }
    if (hook == null) {
      throw new NullPointerException("CDSService hook cannot be null");
    }
    if (description == null) {
      throw new NullPointerException("CDSService description cannot be null");
    }
    this.id = id;
    this.hook = hook;
    this.title = title;
    this.description = description;
    this.prefetchElements = prefetchElements;
    prefetch = new Prefetch();
    for (PrefetchTemplateElement prefetchElement : prefetchElements) {
      this.prefetch.put(prefetchElement.getKey(), prefetchElement.getQuery());
    }
    this.fhirComponents = fhirComponents;
    this.extension = extension;
  }

  public DiscoveryExtension getExtension() { return extension; }

  public List<PrefetchTemplateElement> getPrefetchElements() {
    return prefetchElements;
  }

  /**
   * Performs generic operations for incoming requests of any type.
   *
   * @param request the generically typed incoming request
   * @return The response from the server
   */
  public CdsResponse handleRequest(@Valid @RequestBody requestTypeT request, URL applicationBaseUrl) {
    // create the RequestLog
    RequestLog requestLog = new RequestLog(request, new Date().getTime(),
        this.fhirComponents.getFhirVersion().toString(), this.id, requestService, 5);

    // Parsed request
    requestLog.advanceTimeline(requestService);

    PrefetchHydrator prefetchHydrator = new PrefetchHydrator(this, request, this.fhirComponents);
    prefetchHydrator.hydrate();

    // hydrated
    requestLog.advanceTimeline(requestService);

    // Attempt a Query Batch Request to backfill missing attributes.
    if (myConfig.isQueryBatchRequest()) {
      QueryBatchRequest qbr = new QueryBatchRequest(this.fhirComponents);
      this.attempQueryBatchRequest(request, qbr);
    }

    logger.info("***** ***** request from requestLog: " + requestLog.toString() );

    CdsResponse response = new CdsResponse();
    CardBuilder cardBuilder = new CardBuilder();
    Bundle resources = getPrefetchResources(request);
    
    		
    // CQL Fetched
    List<CoverageRequirementRuleResult> lookupResults;
    
    try {
    	
      lookupResults = new CdsResults().executeCds(resources,myConfig);
//      lookupResults = this.createCqlExecutionContexts(request, fileStore, applicationBaseUrl.toString() + "/");
      requestLog.advanceTimeline(requestService);
    } catch (RequestIncompleteException e) {
      logger.warn("RequestIncompleteException " + request);
      logger.warn(e.getMessage() + "; summary card sent to client");
      response.addCard(cardBuilder.summaryCard(CardTypes.COVERAGE, e.getMessage()));
      requestLog.setCardListFromCards(response.getCards());
      requestLog.setResults(e.getMessage());
      requestService.edit(requestLog);
      return response;
    }

    // process the extension for the configuration
    Configuration hookConfiguration = new Configuration(); // load hook configuration with default values
    Extension extension = request.getExtension();
    if (extension != null) {
      if (extension.getConfiguration() != null) {
        hookConfiguration = extension.getConfiguration();
      }
    }

    boolean errorCardOnEmpty = !(request instanceof OrderSelectRequest);

    // no error cards on empty when order-select request

    boolean foundApplicableRule = false;
    for (CoverageRequirementRuleResult lookupResult : lookupResults) {
      requestLog.addTopic(requestService, lookupResult.getTopic());
//      CqlResultsForCard results = executeCqlAndGetRelevantResults(lookupResult.getContext(), lookupResult.getTopic());
      CqlResultsForCard results = lookupResult.getCqlResultsForCard();
      CoverageRequirements coverageRequirements = results.getCoverageRequirements();
      cardBuilder.setDeidentifiedResourcesContainsPhi(lookupResult.getDeidentifiedResourceContainsPhi());

      if (results.ruleApplies()) {
        foundApplicableRule = true;

        if (results.getCoverageRequirements().getApplies()) {

          // if prior auth already approved
          if (coverageRequirements.isPriorAuthApproved()) {
            response.addCard(cardBuilder.priorAuthCard(results, results.getRequest(), fhirComponents, coverageRequirements.getPriorAuthId(),
                request.getContext().getPatientId(), lookupResult.getCriteria().getPayorId(), request.getContext().getUserId(),
                applicationBaseUrl.toString() + "/fhir/" + fhirComponents.getFhirVersion().toString(),
                fhirResourceRepository));

          } else if (coverageRequirements.isDocumentationRequired() || coverageRequirements.isPriorAuthRequired()) {
            if (StringUtils.isNotEmpty(coverageRequirements.getQuestionnaireOrderUri())
                || StringUtils.isNotEmpty(coverageRequirements.getQuestionnaireFaceToFaceUri())
                || StringUtils.isNotEmpty(coverageRequirements.getQuestionnaireLabUri())
                || StringUtils.isNotEmpty(coverageRequirements.getQuestionnaireProgressNoteUri())
                || StringUtils.isNotEmpty(coverageRequirements.getQuestionnairePARequestUri())
                || StringUtils.isNotEmpty(coverageRequirements.getQuestionnairePlanOfCareUri())
                || StringUtils.isNotEmpty(coverageRequirements.getQuestionnaireDispenseUri())
                || StringUtils.isNotEmpty(coverageRequirements.getQuestionnaireAdditionalUri())) {
              List<Link> smartAppLinks = createQuestionnaireLinks(request, applicationBaseUrl, lookupResult, results);

              if (coverageRequirements.isPriorAuthRequired()) {
                Card card = cardBuilder.transform(CardTypes.PRIOR_AUTH, results, smartAppLinks);
                card.addSuggestionsItem(cardBuilder.createSuggestionWithNote(card, results.getRequest(), fhirComponents, 
                    "Save Update To EHR", "Update original " + results.getRequest().fhirType() + " to add note",
                    true, CoverageGuidance.ADMIN));
                response.addCard(card);
              } else if (coverageRequirements.isDocumentationRequired()) {
                Card card = cardBuilder.transform(CardTypes.DTR_CLIN, results, smartAppLinks);
                card.addSuggestionsItem(cardBuilder.createSuggestionWithNote(card, results.getRequest(), fhirComponents, 
                    "Save Update To EHR", "Update original " + results.getRequest().fhirType() + " to add note",
                    true, CoverageGuidance.CLINICAL));
                response.addCard(card);
              }

              // add a card for an alternative therapy if there is one
              if (results.getAlternativeTherapy().getApplies() && hookConfiguration.getAlternativeTherapy()) {
                try {
                  response.addCard(cardBuilder.alternativeTherapyCard(results.getAlternativeTherapy(),
                      results.getRequest(), fhirComponents));
                } catch (RuntimeException e) {
                  logger.warn("Failed to process alternative therapy: " + e.getMessage());
                }
              }
            } else {
              logger.warn("Unspecified Questionnaire URI; summary card sent to client");
              response.addCard(cardBuilder.transform(CardTypes.COVERAGE, results));
            }
          } else {
            // no prior auth or documentation required
            logger.info("Add the no doc or prior auth required card");
            Card card = cardBuilder.transform(CardTypes.COVERAGE, results);
            card.addSuggestionsItem(cardBuilder.createSuggestionWithNote(card, results.getRequest(), fhirComponents,
                "Save Update To EHR", "Update original " + results.getRequest().fhirType() + " to add note",
                true, CoverageGuidance.COVERED));
            card.setSelectionBehavior(Card.SelectionBehaviorEnum.ANY);
            response.addCard(card);
          }
        }

        // apply the DrugInteractions
        if (results.getDrugInteraction().getApplies()) {
          response.addCard(cardBuilder.drugInteractionCard(results.getDrugInteraction(), results.getRequest()));
        }
      }
    }

    // CQL Executed
    requestLog.advanceTimeline(requestService);

    if (errorCardOnEmpty) {
      if (!foundApplicableRule) {
        String msg = "No documentation rules found";
        logger.warn(msg + "; summary card sent to client");
        response.addCard(cardBuilder.summaryCard(CardTypes.COVERAGE, msg));
      }
      cardBuilder.errorCardIfNonePresent(CardTypes.COVERAGE, response);
    }

    // Ading card to requestLog
    requestLog.setCardListFromCards(response.getCards());
    requestService.edit(requestLog);
    System.out.println("===========In card response========");
    return response.extractSystemActions();
  }

  private List<Link> createQuestionnaireLinks(requestTypeT request, URL applicationBaseUrl,
      CoverageRequirementRuleResult lookupResult, CqlResultsForCard results) {
    List<Link> listOfLinks = new ArrayList<>();
    CoverageRequirements coverageRequirements = results.getCoverageRequirements();
    if (StringUtils.isNotEmpty(coverageRequirements.getQuestionnaireOrderUri())) {
      listOfLinks.add(smartLinkBuilder(request.getContext().getPatientId(), request.getFhirServer(), applicationBaseUrl,
          coverageRequirements.getQuestionnaireOrderUri(), coverageRequirements.getRequestId(),
          lookupResult.getCriteria(), coverageRequirements.isPriorAuthRequired(), "Order Form"));
    }
    if (StringUtils.isNotEmpty(coverageRequirements.getQuestionnaireFaceToFaceUri())) {
      listOfLinks.add(smartLinkBuilder(request.getContext().getPatientId(), request.getFhirServer(), applicationBaseUrl,
          coverageRequirements.getQuestionnaireFaceToFaceUri(), coverageRequirements.getRequestId(),
          lookupResult.getCriteria(), coverageRequirements.isPriorAuthRequired(), "Face to Face Encounter Form"));
    }
    if (StringUtils.isNotEmpty(coverageRequirements.getQuestionnaireLabUri())) {
      listOfLinks.add(smartLinkBuilder(request.getContext().getPatientId(), request.getFhirServer(), applicationBaseUrl,
          coverageRequirements.getQuestionnaireLabUri(), coverageRequirements.getRequestId(),
          lookupResult.getCriteria(), coverageRequirements.isPriorAuthRequired(), "Lab Form"));
    }
    if (StringUtils.isNotEmpty(coverageRequirements.getQuestionnaireProgressNoteUri())) {
      listOfLinks.add(smartLinkBuilder(request.getContext().getPatientId(), request.getFhirServer(), applicationBaseUrl,
          coverageRequirements.getQuestionnaireProgressNoteUri(), coverageRequirements.getRequestId(),
          lookupResult.getCriteria(), coverageRequirements.isPriorAuthRequired(), "Progress Note"));
    }

    if (StringUtils.isNotEmpty(coverageRequirements.getQuestionnairePARequestUri())) {
      listOfLinks.add(smartLinkBuilder(request.getContext().getPatientId(), request.getFhirServer(), applicationBaseUrl,
          coverageRequirements.getQuestionnairePARequestUri(), coverageRequirements.getRequestId(),
          lookupResult.getCriteria(), coverageRequirements.isPriorAuthRequired(), "PA Request"));
    }

    if (StringUtils.isNotEmpty(coverageRequirements.getQuestionnairePlanOfCareUri())) {
      listOfLinks.add(smartLinkBuilder(request.getContext().getPatientId(), request.getFhirServer(), applicationBaseUrl,
          coverageRequirements.getQuestionnairePlanOfCareUri(), coverageRequirements.getRequestId(),
          lookupResult.getCriteria(), coverageRequirements.isPriorAuthRequired(), "Plan of Care/Certification"));
    }

    if (StringUtils.isNotEmpty(coverageRequirements.getQuestionnaireDispenseUri())) {
      listOfLinks.add(smartLinkBuilder(request.getContext().getPatientId(), request.getFhirServer(), applicationBaseUrl,
          coverageRequirements.getQuestionnaireDispenseUri(), coverageRequirements.getRequestId(),
          lookupResult.getCriteria(), coverageRequirements.isPriorAuthRequired(), "Dispense Form"));
    }

    if (StringUtils.isNotEmpty(coverageRequirements.getQuestionnaireAdditionalUri())) {
      listOfLinks.add(smartLinkBuilder(request.getContext().getPatientId(), request.getFhirServer(), applicationBaseUrl,
          coverageRequirements.getQuestionnaireAdditionalUri(), coverageRequirements.getRequestId(),
          lookupResult.getCriteria(), coverageRequirements.isPriorAuthRequired(), "Additional Form"));
    }
    return listOfLinks;
  }

  protected Object evaluateStatement(String statement, Context context) {
    try {
      return context.resolveExpressionRef(statement).evaluate(context);
      // can be thrown if statement is not defined in the cql
    } catch (IllegalArgumentException e) {
      logger.error(e.toString());
      return null;
    } catch (Exception e) {
      logger.error(e.toString());
      return null;
    }
  }

  private Link smartLinkBuilder(String patientId, String fhirBase, URL applicationBaseUrl, String questionnaireUri,
      String reqResourceId, CoverageRequirementRuleCriteria criteria, boolean priorAuthRequired, String label) {
    URI configLaunchUri = myConfig.getLaunchUrl();
    if (!questionnaireUri.startsWith("http")) {
    	questionnaireUri = applicationBaseUrl + "/fhir/r4/" + questionnaireUri;
    }
    

    String launchUrl;
    if (myConfig.getLaunchUrl().isAbsolute()) {
      launchUrl = myConfig.getLaunchUrl().toString();
    } else {
      try {
        launchUrl = new URL(applicationBaseUrl.getProtocol(), applicationBaseUrl.getHost(),
            applicationBaseUrl.getPort(), applicationBaseUrl.getFile() + configLaunchUri.toString(), null).toString();
      } catch (MalformedURLException e) {
        String msg = "Error creating smart launch URL";
        logger.error(msg);
        throw new RuntimeException(msg);
      }
    }

    // remove the trailing '/' if there is one
    if (fhirBase != null && fhirBase.endsWith("/")) {
      fhirBase = fhirBase.substring(0, fhirBase.length() - 1);
    }
    if (patientId != null && patientId.startsWith("Patient/")) {
      patientId = patientId.substring(8);
    }

    // PARAMS:
    // template is the uri of the questionnaire
    // request is the ID of the device request or medrec (not the full URI like the
    // IG says, since it should be taken from fhirBase

    //Change template to questionnaire and request to order
    String appContext = "";
    
    if (myConfig.getUrlEncodeAppContext()) {
      appContext = "questionnaire=" + questionnaireUri + "&order=" + reqResourceId;
      appContext = appContext + "&fhirpath=" + applicationBaseUrl + "/fhir/";

      appContext = appContext + "&priorauth=" + (priorAuthRequired ? "true" : "false");
      appContext = appContext + "&filepath=" + applicationBaseUrl + "/";
      logger.info("CdsService::smartLinkBuilder: URL encoding appcontext");
      try {
        appContext = URLEncoder.encode(appContext, StandardCharsets.UTF_8.name()).toString();
      } catch (UnsupportedEncodingException e) {
        logger.error("CdsService::smartLinkBuilder: failed to encode URL: " + e.getMessage());
      }
    } else {
      JSONObject appContexObject = new JSONObject();
      appContexObject.put("questionnaire",  questionnaireUri);
      appContexObject.put("fhirpath", applicationBaseUrl + "/fhir/");
      appContexObject.put("order", reqResourceId);
      appContexObject.put("priorauth", (priorAuthRequired ? "true" : "false"));
      appContexObject.put("filepath", applicationBaseUrl + "/");
      appContext = appContexObject.toJSONString();

    }

    logger.info("smarLinkBuilder: appContext: " + appContext);

    if (myConfig.isAppendParamsToSmartLaunchUrl()) {
      launchUrl = launchUrl + "?iss=" + fhirBase + "&patientId=" + patientId + "&template=" + questionnaireUri
          + "&request=" + reqResourceId;
    } else {
      // TODO: The iss should be set by the EHR?
      //launchUrl = launchUrl;
    }

    Link link = new Link();
    link.setType("smart");
    link.setLabel(label);
    link.setUrl(launchUrl);

    link.setAppContext(appContext);

    return link;
  }

  // Implement these in child class
  public abstract List<CoverageRequirementRuleResult> createCqlExecutionContexts(requestTypeT request,
      FileStore fileStore, String baseUrl) throws RequestIncompleteException;

  protected abstract CqlResultsForCard executeCqlAndGetRelevantResults(Context context, String topic);

  /**
   * Delegates query batch request to child classes based on their prefetch types.
   */
  protected abstract void attempQueryBatchRequest(requestTypeT request, QueryBatchRequest qbr);

public Bundle getPrefetchResources(@Valid requestTypeT request) {
	// TODO Auto-generated method stub
	return null;
}
protected Bundle  checkUpdateBundle(Bundle bundle, Bundle draftOrders) {
  
  return bundle;
}

}