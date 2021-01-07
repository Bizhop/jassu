package fi.bizhop.jassu.models;

import java.util.List;

public class KirvesGameOut {
    private String admin;
    private List<KirvesPlayerOut> players;
    private String message;
    private int cardsInDeck;
    private String turn;
    private String dealer;
    private List<String> myCardsInHand;

    public KirvesGameOut(String admin, List<KirvesPlayerOut> players, int cardsInDeck, String dealer, String turn, List<String> myCardsInHand, String message) {
        this.admin = admin;
        this.players = players;
        this.cardsInDeck = cardsInDeck;
        this.dealer = dealer;
        this.turn = turn;
        this.myCardsInHand = myCardsInHand;
        this.message = message;
    }

    public KirvesGameOut(String message) {
        this.message = message;
    }

    public String getAdmin() {
        return admin;
    }

    public List<KirvesPlayerOut> getPlayers() {
        return players;
    }

    public String getMessage() {
        return message;
    }

    public int getCardsInDeck() { return cardsInDeck; }

    public String getTurn() {
        return turn;
    }

    public String getDealer() {
        return dealer;
    }

    public List<String> getMyCardsInHand() {
        return myCardsInHand;
    }
}
