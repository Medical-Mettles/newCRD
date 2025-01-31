package org.cdshooks;

import java.util.ArrayList;
import java.util.List;

public class CdsResponse {

  /**
   * An array of Cards. Cards can provide a combination of information (for reading), suggested
   * actions (to be applied if a user selects them), and links (to launch an app if the user selects
   * them). The EHR decides how to display cards, but we recommend displaying suggestions using
   * buttons, and links using underlined text. REQUIRED
   */
  private List<Card> cards = new ArrayList<Card>();
  private List<Action> systemActions = new ArrayList<Action>();
  

  /**
   * Add a card.
   * @param cardsItem The card.
   * @return
   */
  public CdsResponse addCard(Card cardsItem) {
    this.cards.add(cardsItem);
    return this;
  }

  public List<Card> getCards() {
    return cards;
  }

  public void setCards(List<Card> cards) {
    this.cards = cards;
  }


  /**
   * Add a SystemAction
   * @param cardsItem The card.
   * @return
   */
  public CdsResponse addSystemAction(Action actionItem) {
    this.systemActions.add(actionItem);
    return this;
  }

  public List<Action> getSystemActions() {
    return systemActions;
  }

  public void setSystemActions(List<Action> actions) {
    this.systemActions = actions;
  }

  public CdsResponse extractSystemActions() {
    for (Card card : cards) {
      System.out.println("adding card " + card.getSummary());
      if (card.getSuggestions() != null) {
        for (Suggestion suggestion : card.getSuggestions()) {
          System.out.println("adding suggestion " + suggestion.getLabel());
          for (Action action : suggestion.getActions()) {
            System.out.println("adding action " + action.getResourceId());
            this.addSystemAction(action);
          }
        }
      }
    }
    return this;
    
  }




}
