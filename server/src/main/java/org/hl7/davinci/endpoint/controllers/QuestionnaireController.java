package org.hl7.davinci.endpoint.controllers;

import org.hl7.davinci.endpoint.Application;
import org.hl7.davinci.endpoint.files.FileResource;
import org.hl7.davinci.endpoint.files.FileStore;
import org.hl7.fhir.instance.model.api.IDomainResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.davinci.r4.FhirComponents;

import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import org.hl7.davinci.endpoint.Utils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;

import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemAnswerOptionComponent;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemComponent;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseStatus;
import org.hl7.fhir.r4.model.Reference;

// --- ORDER OF RESPONSE-REQUEST OPERATIONS
// (REQUEST) External user sends the initial QuestionnaireResponse JSON that contains which questionnaire it would like to trigger as n element the "contained" field.
// (RESPONSE) QuestionnaireController adds the first question with its answerResponse options (with its linkId and text) to the JSON in QuestionnaireResponse.contained.item[] and sends it back.
// (REQUEST) External user adds their answer to the question to the JSON in QuestionnaireResponse.item[] and sends it back.
// (RESPONSE) QuestionnaireController takes that response and adds the next indicated question to the JSON in QuestionnaireResponse.contained.item[] and sends it back.
// Repeat intil QuestionnaireController reaches a leaf-node, then it sets the status to "completed" from "in-progress"
// Ultimately, The QuestionnaireController responses add ONLY to the QuestionnaireResponse.contained.item[]. The external requester adds answers to QuestionnaireResponse.item[] and includes the associated linkid and text.

@CrossOrigin
@RestController
@RequestMapping("/Questionnaire")
public class QuestionnaireController {

    @Autowired
    private FileStore fileStore;

    /**
     * An inner class that demos a tree to define next questions based on responses.
     */
    private class AdaptiveQuestionnaireTree {
        
        // The current question (defined within the node).
        private AdaptiveQuestionnaireNode root;
    
        /**
         * Initial constructor that generates the beginning of the tree.
         * @param inputQuestionnaire    The input questionnaire from the CDS-Library.
         */
        public AdaptiveQuestionnaireTree(Questionnaire inputQuestionnaire) {

            // Top level parent question item, the first question page.
            QuestionnaireItemComponent topLevelQuestion = inputQuestionnaire.getItemFirstRep();

            // Start the root building.
            this.root = new AdaptiveQuestionnaireNode(topLevelQuestion);
        }

        /**
         * Returns the next question based on the response to the current question. Also sets the next question based on that response.
         * @param allAnswerItems  The set of answer items given to this tree.
         * @return
         */
        public List<QuestionnaireItemComponent> getNextQuestionsForAnswers(List<QuestionnaireResponseItemComponent> allResponseItems) {
            if(allResponseItems == null) {
                throw new NullPointerException("Input answer items is null.");
            }
            return this.root.getNextQuestionForAnswers(allResponseItems);
        }

        /**
         * TODO Returns whether this has reached a leaf node. TODO - NEEDS TO BE UPDATED
         * @param response
         * @return
         */
        public boolean reachedLeafNode() {
            return this.root.isLeafNode();
        }
    
        /**
         * Inner class that describes a node of the tree.
         */
        private class AdaptiveQuestionnaireNode {

            // Contains the list of additional questions that should be displayed with this question.
            private List<QuestionnaireItemComponent> supplementalQuestions;
            // Contains the current question item that dictates the next question of the node.
            private QuestionnaireItemComponent determinantQuestionItem;
            // Map of (answerResponse->childQuestionItemNode) (The child could have answer options within it or be a leaf node. It does have a question item component though).
            private Map<String, AdaptiveQuestionnaireNode> children;

            /**
             * Constructor
             * @param determinantQuestionItem
             */
            public AdaptiveQuestionnaireNode(QuestionnaireItemComponent determinantQuestion) {

                this.determinantQuestionItem = determinantQuestion;
                // Get the child and supplemental question items of this question.
                List<QuestionnaireItemComponent> subQuestions = determinantQuestion.getItem();
                // Extract the supplemental questions which do not have a child link-id branch from the determinant questions.
                List<String> nonSupplementLinkIds = determinantQuestionItem.getAnswerOption().stream().map(answerOption -> answerOption.getModifierExtensionFirstRep().getUrl()).collect(Collectors.toList());
                List<QuestionnaireItemComponent> childQuestions = this.extractChildQuestions(subQuestions, nonSupplementLinkIds);
                // Extract the remaining questions as supplemental questions.
                this.supplementalQuestions = this.extractSupplementalQuestions(subQuestions, nonSupplementLinkIds);

                // The number of answer options of the determinant question should always equal the number of child question items.
                if((this.determinantQuestionItem.getAnswerOption().size() != childQuestions.size())){
                    throw new RuntimeException("There should be the same number of answer options as sub-items. Answer options: " + this.determinantQuestionItem.getAnswerOption().size() + ", sub-items: " + childQuestions.size());
                }

                // If the determinant question item does not have any answer options, then this is a leaf node and should not generate any children.
                if(determinantQuestionItem.hasAnswerOption()) {
                    Map<String, String> childIdsToResponses = new HashMap<String, String>();
                    // This loop iterates over the possible answer options of this questionitem and links the linkId to its possible responses.
                    for(QuestionnaireItemAnswerOptionComponent answerOption : determinantQuestionItem.getAnswerOption()) {
                        // The Id of this answer response's next question.
                        String answerNextQuestionId = answerOption.getModifierExtensionFirstRep().getUrl();
                        // The response that indicates this answer to the question.
                        String possibleAnswerResponse = answerOption.getValueCoding().getCode();
                        // Check for issues.
                        if(answerNextQuestionId == null || possibleAnswerResponse == null){
                            throw new RuntimeException("Malformed Adaptive Questionnaire. Missing a questionID or answer response.");
                        }
                        // Add the key-value pair of next question id to its assocated answer response.
                        childIdsToResponses.put(answerNextQuestionId, possibleAnswerResponse);
                    }

                    // Create the map of answerResponses->subQuestionItems
                    this.children = new HashMap<String, AdaptiveQuestionnaireNode>();
                    List<QuestionnaireItemComponent> subQuestionItems = determinantQuestionItem.getItem();
                    for(QuestionnaireItemComponent subQuestionItem : subQuestionItems){
                        // SubQuestion linkId.
                        String subQuestionLinkId = subQuestionItem.getLinkId();
                        // SubQuestion's associated response.
                        String subQuestionResponse = childIdsToResponses.get(subQuestionLinkId);
                        // Create a new node for this subQuestion.
                        AdaptiveQuestionnaireNode subQuestionNode = new AdaptiveQuestionnaireNode(subQuestionItem);
                        this.children.put(subQuestionResponse, subQuestionNode);
                    }
                }
            }

            /**
             * Returns the next question based on the set of provided answers.
             * @param allResponseItems
             * @return
             */
            public List<QuestionnaireItemComponent> getNextQuestionForAnswers(List<QuestionnaireResponseItemComponent> allResponseItems) {

                // Extract the current question being answered from the list if answer items.
                String currentQuestionId = this.determinantQuestionItem.getLinkId();
                List<QuestionnaireResponseItemComponent> currentQuestionResponses = allResponseItems.stream().filter(answerItem -> answerItem.getLinkId().equals(currentQuestionId)).collect(Collectors.toList());
                if(currentQuestionResponses.size() != 1) {
                    // If there are no more answer items to check, we've reached the end of the recursion.
                    return this.getQuestionSet();
                }

                QuestionnaireResponseItemComponent currentQuestionResponse = currentQuestionResponses.get(0);
                QuestionnaireResponseItemAnswerComponent currentQuestionAnswer = currentQuestionResponse.getAnswerFirstRep();

                // With the currrent question answer in hand, extract the next question.
                AdaptiveQuestionnaireNode nextNode = this.children.get(currentQuestionAnswer.getValueCoding().getCode());
                
                return nextNode.getNextQuestionForAnswers(allResponseItems.stream().filter(responseItem -> !responseItem.equals(currentQuestionResponse)).collect(Collectors.toList()));
            }

            /**
             * Returns the question items in the given list that do not have the linkids of the given list of strings.
             * @param questionItems
             * @param nonSupplementQuestions
             * @return
             */
            private List<QuestionnaireItemComponent> extractSupplementalQuestions(
                    List<QuestionnaireItemComponent> questionItems, List<String> nonSupplementLinkIds) {
                return questionItems.stream().filter(questionItem -> !nonSupplementLinkIds.contains(questionItem.getLinkId())).collect(Collectors.toList());
            }

            /**
             * Returns the question items in the given list that do have the linkids of the given list of strings.
             * @param questionItems
             * @param nonSupplementQuestions
             * @return
             */
            private List<QuestionnaireItemComponent> extractChildQuestions(
                    List<QuestionnaireItemComponent> questionItems, List<String> nonSupplementLinkIds) {
                return questionItems.stream().filter(questionItem -> nonSupplementLinkIds.contains(questionItem.getLinkId())).collect(Collectors.toList());
            }

            /**
             * Returns the set of questions associated with the node. Incldues all questions in the set, determinant and non-determinant.
             * @return
             */
            public List<QuestionnaireItemComponent> getQuestionSet() {
                QuestionnaireItemComponent determinantQuestionNoChildren = this.removeChildrenFromQuestionItem(this.determinantQuestionItem);
                List<QuestionnaireItemComponent> questionSet = new ArrayList<QuestionnaireItemComponent>();
                questionSet.add(determinantQuestionNoChildren);
                questionSet.addAll(this.supplementalQuestions);
                return questionSet;
            }

            /**
             * Returns a new question item that is indentical to the input qusetion item except without the children.
             * @param inputQuestionItem
             * @return
             */
            private QuestionnaireItemComponent removeChildrenFromQuestionItem(QuestionnaireItemComponent inputQuestionItem){
                QuestionnaireItemComponent questionItemNoChildren = new QuestionnaireItemComponent();
                questionItemNoChildren.setLinkId(inputQuestionItem.getLinkId());
                questionItemNoChildren.setText(inputQuestionItem.getText());
                questionItemNoChildren.setType(inputQuestionItem.getType());
                questionItemNoChildren.setRequired(inputQuestionItem.getRequired());
                questionItemNoChildren.setAnswerOption(inputQuestionItem.getAnswerOption());
                return questionItemNoChildren;
            }

            /**
             * TODO Returns whether this questionniare is a leaf node. TODO - NEEDS TO BE UPDATED.
             * @return
             */
            private boolean isLeafNode() {
                return this.children == null || this.children.size() < 1;
            }

        }

    }

    // Logger.
    private static Logger logger = Logger.getLogger(Application.class.getName());
    // Trees that track the current and next questions. Is key-value mappng of: Map<Questionnaire ID -> AdaptiveQuestionnaireTree>
    private static final Map<String, AdaptiveQuestionnaireTree> questionnaireTrees = new HashMap<String, AdaptiveQuestionnaireTree>();

    /**
     * Retrieves the next question based on the request.
     * @param request
     * @param entity
     * @return
     */
    @PostMapping(value = "/$next-question", consumes = { MediaType.APPLICATION_JSON_VALUE, "application/fhir+json" })
    public ResponseEntity<String> retrieveNextQuestion(HttpServletRequest request, HttpEntity<String> entity) {
        return getNextQuestionOperation(entity.getBody(), request);
    }

    /**
     * Returns the next question based on the request.
     * @param body
     * @param request
     * @return
     */
    private ResponseEntity<String> getNextQuestionOperation(String body, HttpServletRequest request) {
        logger.info("POST /Questionnaire/$next-question fhir+");

        FhirContext ctx = new FhirComponents().getFhirContext();
        IParser parser = ctx.newJsonParser();

        // Parses the body.
        IDomainResource domainResource = (IDomainResource) parser.parseResource(QuestionnaireResponse.class, body);
        if (!domainResource.fhirType().equalsIgnoreCase("QuestionnaireResponse")) {
            logger.warning("unsupported resource type: ");
            HttpStatus status = HttpStatus.BAD_REQUEST;
            MediaType contentType = MediaType.TEXT_PLAIN;
            return ResponseEntity.status(status).contentType(contentType).body("Bad Request");
        } else {
            logger.info(" ---- Resource received " + domainResource.toString());
            QuestionnaireResponse inputQuestionnaireResponse = (QuestionnaireResponse) domainResource;
            String fragmentId = inputQuestionnaireResponse.getQuestionnaire();
            List<Resource> containedResource = inputQuestionnaireResponse.getContained();
            Questionnaire inputQuestionnaireFromRequest = null;
            for (int i = 0; i < containedResource.size(); i++) {
                Resource item = containedResource.get(i);
                if (item.getResourceType().equals(ResourceType.Questionnaire)) {
                    Questionnaire checkInputQuestionnaire = (Questionnaire) item;
                    if (checkInputQuestionnaire.getId().equals(fragmentId)) {
                        inputQuestionnaireFromRequest = checkInputQuestionnaire;
                        break;
                    }
                }
            }

            String questionnaireId = ((Reference) inputQuestionnaireResponse.getExtensionByUrl("http://hl7.org/fhir/StructureDefinition/contained-id").getValue()).getReference();
            System.out.println("Input Questionnaire: " + questionnaireId);

            if (inputQuestionnaireFromRequest != null) {

                if(!questionnaireTrees.containsKey(questionnaireId)){
                    // If there is not already a tree that matches the requested questionnaire id, build it.
                    // Import the requested CDS-Library Questionnaire.
                    Questionnaire cdsQuestionnaire = null;
                    try {
                        //TODO: need to determine topic, filename, and fhir version without having them hard coded
                        boolean pullFromResources = false;
                        if(pullFromResources){
                            // Resource is pulled from the file store as a resource.
                            String baseUrl = Utils.getApplicationBaseUrl(request).toString() + "/";
                            FileResource fileResource = fileStore.getFhirResourceById("r4", "questionnaire", "HomeOxygenTherapyAdditional", baseUrl);
                            org.springframework.core.io.Resource resource = fileResource.getResource();
                            InputStream resourceStream = resource.getInputStream();
                            cdsQuestionnaire = (Questionnaire) parser.parseResource(resourceStream);
                        } else {
                            // File is pulled from the file store as a file.
                            FileResource fileResource = fileStore.getFile("HomeOxygenTherapy", "Questions-HomeOxygenTherapyAdditionalAdaptive.json", "R4", false);
                            if(fileResource == null) {
                                throw new RuntimeException("File resource pulled from the filestore is null.");
                            }
                            if(fileResource.getResource() == null) {
                                throw new RuntimeException("File resource pulled from the filestore has a null getResource().");
                            }
                            cdsQuestionnaire = (Questionnaire) parser.parseResource(fileResource.getResource().getInputStream());
                        }

                        logger.info("--- Imported Questionnaire " + cdsQuestionnaire.getId());
                    } catch (DataFormatException e) {
                        e.printStackTrace();
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    // Build the tree.
                    AdaptiveQuestionnaireTree newTree = new AdaptiveQuestionnaireTree(cdsQuestionnaire);
                    questionnaireTrees.put(questionnaireId, newTree);
                    logger.info("--- Built Questionnaire Tree for " + questionnaireId);
                }

                // Pull the tree for the requested questionnaire id.
                AdaptiveQuestionnaireTree currentTree = questionnaireTrees.get(questionnaireId);
                // Get the request's set of answer responses.
                List<QuestionnaireResponseItemComponent> allResponses = inputQuestionnaireResponse.getItem();
                // Pull the resulting next question that the recieved responses and answers point to from the tree without including its children.
                List<QuestionnaireItemComponent> nextQuestionSetResults = currentTree.getNextQuestionsForAnswers(allResponses);
                // Add the next set of questions to the response.
                QuestionnaireController.addQuestionSetToQuestionnaireResponse(inputQuestionnaireResponse, nextQuestionSetResults);

                logger.info("--- Added next question set for questionnaire \'" + questionnaireId + "\' for response \'" + allResponses + "\'.");

                // If this question is a leaf node and is the final question, set the status to "completed"
                if (currentTree.reachedLeafNode()) {
                    inputQuestionnaireResponse.setStatus(QuestionnaireResponseStatus.COMPLETED);
                    logger.info("--- Questionnaire leaf node reached, setting status to \"completed\".");
                }

                logger.info("---- Get meta profile: " + inputQuestionnaireFromRequest.getMeta().getProfile().get(0).getValue());
                logger.info("---- Sending response: " + inputQuestionnaireFromRequest.getId());

                // Build and send the response.
                String formattedResourceString = ctx.newJsonParser().encodeResourceToString(inputQuestionnaireResponse);
                return ResponseEntity.status(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.CONTENT_TYPE, "application/fhir+json" + "; charset=utf-8")
                        .body(formattedResourceString);
            } else {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.CONTENT_TYPE, "application/fhir+json" + "; charset=utf-8")
                        .body("Input questionnaire from the request does not exist.");
            }
        }
    }

    /**
     * Adds the given set of questions to the contained questionniare in the questionnaire response.
     * @param inputQuestionnaireResponse
     * @param questionSet
     */
    private static void addQuestionSetToQuestionnaireResponse(QuestionnaireResponse inputQuestionnaireResponse, List<QuestionnaireItemComponent> questionSet) {
        // Add the next question set to the QuestionnaireResponse.contained[0].item[].
        Questionnaire containedQuestionnaire = (Questionnaire) inputQuestionnaireResponse.getContained().get(0);
        questionSet.forEach(questionItem -> containedQuestionnaire.addItem(questionItem));
    }
}