package org.hl7.davinci.endpoint.cdshooks.services.crd;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.cdshooks.AlternativeTherapy;
import org.cdshooks.CoverageRequirements;
import org.cdshooks.DrugInteraction;
import org.hl7.ShortNameMaps;
import org.hl7.davinci.endpoint.components.CardBuilder.CqlResultsForCard;
import org.hl7.davinci.endpoint.config.YamlConfig;
import org.hl7.davinci.endpoint.rules.CoverageRequirementRuleResult;
import org.hl7.davinci.r4.CardTypes;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.StringType;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.opencds.cqf.cql.engine.runtime.Code;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.context.FhirContext;

public class CdsResults {
	YamlConfig myConfig;
	FhirContext fhirContext = FhirContext.forR4();
	private Bundle BundleResources;
	static final Logger logger = LoggerFactory.getLogger(CdsResults.class);
	static final String DESC = "description";

	public CdsResults() {

	}

	public List<CoverageRequirementRuleResult> executeCds(Bundle resources, YamlConfig myConfig) {
		this.myConfig = myConfig;
		this.BundleResources = resources;
		if (resources != null) {
			JSONArray resultobj = postResources(resources);
			if (resultobj != null) {
				return coverageResults(resultobj);

			}
		}
		return null;
	}

	private List<CoverageRequirementRuleResult> coverageResults(JSONArray resultobj) {
		String topic = "";
		List<CoverageRequirementRuleResult> coverageRules = new ArrayList<>();
		System.out.println("the sie is: " + resultobj.size());
		for (int j = 0; j < resultobj.size(); j++) {

			JSONArray rObj = (JSONArray) resultobj.get(j);
			logger.info("the sie is: " + rObj.size());
			CoverageRequirementRuleResult coverageRuleResult = new CoverageRequirementRuleResult();
			CqlResultsForCard cqlResults = new CqlResultsForCard();
			CoverageRequirements coverageRequirements = new CoverageRequirements();
			coverageRequirements.setApplies(true);
			AlternativeTherapy alternativeTherapy = new AlternativeTherapy();
			alternativeTherapy.setApplies(false);
			for (int k = 0; k < rObj.size(); k++) {
				cqlResults.setRuleApplies(false);
				JSONObject obj = (JSONObject) rObj.get(k);
				logger.info("tthe key set is: " + obj.keySet());
				if (obj.containsKey("requestId")) {
					coverageRequirements.setRequestId((String) obj.get("requestId"));
					Resource requestResource = getResourcefromBundle(this.BundleResources,
							(String) obj.get("requestId"));
					if (requestResource != null) {
						cqlResults.setRequest(requestResource);
					}

				}
				if (obj.containsKey("template")) {

					String questionaire = (String) obj.get("template");

					if (questionaire != null) {
						coverageRequirements.setQuestionnaireOrderUri(questionaire);
					}

				}
				String humanReadableTopic = StringUtils.join(StringUtils.splitByCharacterTypeCamelCase(topic), ' ');
				if (obj.containsKey("configuration")) {
					
					JSONObject params = (JSONObject) obj.get("configuration");

					Resource resultParams = (Resource) fhirContext.newJsonParser().parseResource(params.toJSONString());
					logger.info("resource type is"+resultParams.getResourceType().name());
					if (resultParams.getResourceType().compareTo(ResourceType.Parameters)==0) {
						cqlResults.setRuleApplies(true);
						Parameters cqlParams = (Parameters)resultParams;
						// If Final Decision is Yes -- Prior Auth is approved

						// check If Prior Auth is required
						coverageRequirements
								.setPriorAuthRequired(getCQLBooleanResults(cqlParams, CardTypes.PRIOR_AUTH.getCode()));
						// check if Documentation is required
						coverageRequirements.setDocumentationRequired(
								getCQLBooleanResults(cqlParams, CardTypes.DOCUMENTATION.getCode()));

						// if prior auth, supercede the documentation required
						if (coverageRequirements.isPriorAuthRequired()) {
							logger.info("Prior Auth Required");
							Object desc = getCQLResults(cqlParams, CardTypes.PRIOR_AUTH.getCode() + DESC);
							coverageRequirements.setSummary(humanReadableTopic + ": Prior Authorization required.");
							if (desc != null) {
								coverageRequirements.setDetails(((StringType) desc).asStringValue());
							}

							// check if prior auth is automatically approved
							if ((((StringType) getCQLResults(cqlParams, "FinalDecision")).asStringValue())
									.equals("YES")) {
								coverageRequirements.setPriorAuthApproved(true);
								if (coverageRequirements.isPriorAuthApproved()) {
									coverageRequirements.generatePriorAuthId();
									logger.info("Prior Auth Approved: " + coverageRequirements.getPriorAuthId());
									coverageRequirements
											.setSummary(humanReadableTopic + ": Prior Authorization approved.")
											.setDetails("Prior Authorization approved, ID is "
													+ coverageRequirements.getPriorAuthId());
								}
							}

						} else if (coverageRequirements.isDocumentationRequired()) {
							logger.info("Documentation Required");
							Object desc = getCQLResults(cqlParams, CardTypes.DOCUMENTATION.getCode() + DESC);
							coverageRequirements.setSummary(humanReadableTopic + ": Documentation Required.");
							if (desc != null) {
								coverageRequirements.setDetails(((StringType) desc).asStringValue());
							}

						} else {
							logger.info("No Prior Auth or Documentation Required");
							coverageRequirements.setSummary(humanReadableTopic + ": No Prior Authorization required.")
									.setDetails("No Prior Authorization required for " + humanReadableTopic + ".");
						}

						alternativeTherapy.setApplies(false);

						// process the alternative therapies
						try {
							if (getCQLResults(cqlParams, "ALTERNATIVE_THERAPY") != null) {
								Object ac = getCQLResults(cqlParams, "ALTERNATIVE_THERAPY");

								Code code = (Code) ac;
								logger.info("alternate therapy suggested: " + code.getDisplay() + " (" + code.getCode()
										+ " / " + ShortNameMaps.CODE_SYSTEM_SHORT_NAME_TO_FULL_NAME.inverse()
												.get(code.getSystem()).toUpperCase()
										+ ")");

								alternativeTherapy.setApplies(true).setCode(code.getCode()).setSystem(code.getSystem())
										.setDisplay(code.getDisplay());
							}
						} catch (Exception e) {
							logger.info("-- No alternative therapy defined");
						}
					} else {
						logger.info("Parameters resource not found");
					}

				}

			}
			cqlResults.setCoverageRequirements(coverageRequirements);
			cqlResults.setAlternativeTherapy(alternativeTherapy);

			// add empty drug interaction
			DrugInteraction drugInteraction = new DrugInteraction();
			drugInteraction.setApplies(false);
			cqlResults.setDrugInteraction(drugInteraction);

			coverageRuleResult.setCqlResultsForCard(cqlResults);
			coverageRules.add(coverageRuleResult);
		}
		logger.info("the size of results is " + coverageRules.size());
		return coverageRules;
	}

	private Resource getResourcefromBundle(Bundle bundle, String resourceId) {
		// System.out.println("looking for resource: " + resourceId);
		Resource res = null;
		for (BundleEntryComponent entry : bundle.getEntry()) {
			// System.out.println("the entry is :" + entry.getResource().fhirType());
			if (entry.getResource().fhirType() == "Bundle") {
				res = getResourcefromBundle((Bundle) entry.getResource(), resourceId);
				if (res != null) {
					return res;
				}
			}
			if (entry.getResource().getIdElement() != null) {
				if (entry.getResource().getIdElement().getIdPart().equals(resourceId)) {
					// System.out.println("found resource for: " + resourceId + "----"
					// + ctx.newJsonParser().encodeResourceToString(entry.getResource()));
					return entry.getResource();
				}
			}

		}
		return null;
	}

	private boolean getCQLBooleanResults(Parameters cqlParams, String code) {
		for (ParametersParameterComponent param : cqlParams.getParameter()) {
			if (param.getName().equals(code)) {
				try {
					BooleanType value = (BooleanType) param.getValue();
					return value.booleanValue();
				} catch (Exception e) {
					logger.info("Failure on Boolean type conversion");
				}

			}

		}
		return false;
	}

	private Object getCQLResults(Parameters cqlParams, String code) {
		for (ParametersParameterComponent param : cqlParams.getParameter()) {
			if (param.getName().equals(code)) {

				return param.getValue();

			}

		}
		return null;
	}

	public JSONArray postResources(Bundle requestsBundle) {
		JSONArray cqlResObj = new JSONArray();

		String cqlJsonStr = fhirContext.newJsonParser().encodeResourceToString(requestsBundle);
		System.out.println("The bundle is " + cqlJsonStr);
		try {

			// cqlJsonStr = fhirContext.newJsonParser().encodeResourceToString(b);
			// System.out.println("The bundle is " + cqlJsonStr);
			logger.info("Trying to connect to " + myConfig.getMettlesCDSUrl());
			URL cqlDataUrl = new URL(myConfig.getMettlesCDSUrl());
			byte[] cqlReqDataBytes = cqlJsonStr.getBytes("UTF-8");
			HttpURLConnection cqlDataconn = (HttpURLConnection) cqlDataUrl.openConnection();
			cqlDataconn.setRequestMethod("POST");
			cqlDataconn.setRequestProperty("Content-Type", "application/json");
			cqlDataconn.setRequestProperty("Accept", "application/json");

			cqlDataconn.setDoOutput(true);
			cqlDataconn.getOutputStream().write(cqlReqDataBytes);
			BufferedReader cqlResReader = new BufferedReader(
					new InputStreamReader(cqlDataconn.getInputStream(), "UTF-8"));
			String line = null;
			StringBuilder cqlResStrBuilder = new StringBuilder();
			while ((line = cqlResReader.readLine()) != null) {
				cqlResStrBuilder.append(line);
			}
			logger.info("the recd string is : " + cqlResStrBuilder.toString());
			JSONParser parser = new JSONParser();
			JSONArray cqlObj = (JSONArray) parser.parse(cqlResStrBuilder.toString());
			cqlResObj.add(cqlObj);

		} catch (Exception e) {
			e.printStackTrace();
		}
		return cqlResObj;
	}

}
