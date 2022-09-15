package org.hl7.davinci.endpoint.rules;

import org.hl7.davinci.endpoint.components.CardBuilder.CqlResultsForCard;
import org.opencds.cqf.cql.engine.execution.Context;

public class CoverageRequirementRuleResult {

  private Context context;
  private CoverageRequirementRuleCriteria criteria;
  private String topic;
  private boolean deidentifiedResourceContainsPhi;
  private CqlResultsForCard cqlResultsForCard;

  public Context getContext() { return context; }

  public CoverageRequirementRuleResult setContext(Context context) {
    this.context = context;
    return this;
  }

  public CoverageRequirementRuleCriteria getCriteria() { return criteria; }

  public CoverageRequirementRuleResult setCriteria(CoverageRequirementRuleCriteria criteria) {
    this.criteria = criteria;
    return this;
  }

  public String getTopic() { return topic; }

  public CoverageRequirementRuleResult setTopic(String topic) {
    this.topic = topic;
    return this;
  }

  public boolean getDeidentifiedResourceContainsPhi() { return deidentifiedResourceContainsPhi; }

  public CoverageRequirementRuleResult setDeidentifiedResourceContainsPhi(boolean deidentifiedResourceContainsPhi) {
    this.deidentifiedResourceContainsPhi = deidentifiedResourceContainsPhi;
    return this;
  }
  public CqlResultsForCard getCqlResultsForCard() { return cqlResultsForCard; }

  public CoverageRequirementRuleResult setCqlResultsForCard(CqlResultsForCard cqlResultsForCard) {
    this.cqlResultsForCard = cqlResultsForCard;
    return this;
  }
}
