package fi.bizhop.jassu.model;

import fi.bizhop.jassu.exception.CardException;
import fi.bizhop.jassu.exception.KirvesGameException;
import fi.bizhop.jassu.util.RandomUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static fi.bizhop.jassu.model.Card.Rank.*;
import static fi.bizhop.jassu.model.Card.Suit.*;
import static fi.bizhop.jassu.model.KirvesGame.Action.*;
import static java.util.stream.Collectors.toList;

public class KirvesGame {
    private static final int NUM_OF_CARD_TO_DEAL = 5;

    private final Long id;
    private final User admin;
    private Cards deck;
    private final List<KirvesPlayer> players = new ArrayList<>();
    private boolean active;
    private boolean canJoin;
    private KirvesPlayer turn;
    private KirvesPlayer dealer;
    private boolean canDeal;
    private String message;
    private int firstPlayerOfRound;
    private Card valttiCard = null;
    private Card.Suit valtti = null;
    private Card cutCard = null;
    private boolean canSetValtti;
    private boolean forcedGame;

    public KirvesGame(User admin, Long id) throws CardException {
        this.id = id;
        this.admin = admin;
        this.deck = new KirvesDeck().shuffle();
        this.active = true;
        KirvesPlayer player = new KirvesPlayer(admin);
        players.add(player);
        setDealer(player, player);
        this.canJoin = true;
        this.canSetValtti = false;
        this.forcedGame = false;
    }

    public KirvesGameOut out() {
        return this.out(null);
    }

    public KirvesGameOut out(User user) {
        List<String> myCards = new ArrayList<>();
        List<String> myActions = new ArrayList<>();
        String myExtraCard = null;
        if(user != null) {
            Optional<KirvesPlayer> me = getPlayer(user);
            if(me.isPresent()) {
                KirvesPlayer player = me.get();
                myCards = player.getHand().getCardsOut();
                myActions = player.getAvailableActions().stream()
                        .map(Enum::name)
                        .collect(toList());
                Card extraCard = player.getExtraCard();
                if(extraCard != null) {
                    myExtraCard = extraCard.toString();
                }
            }
        }
        return new KirvesGameOut(
                this.id,
                this.getAdmin(),
                this.players.stream().map(KirvesPlayerOut::new).collect(toList()),
                this.deck.size(),
                this.dealer.getUserEmail(),
                this.turn.getUserEmail(),
                myCards,
                myExtraCard,
                myActions,
                this.message,
                this.canJoin,
                this.valttiCard == null ? "" : this.valttiCard.toString(),
                this.valtti == null ? "" : this.valtti.toString()
        );
    }

    private Optional<KirvesPlayer> getPlayer(User user) {
        return this.players.stream().filter(player -> player.getUser().equals(user)).findFirst();
    }

    public Optional<KirvesPlayer> getRoundWinner(int round) {
        return this.players.stream().filter(player -> player.getRoundsWon().contains(round)).findFirst();
    }

    public void addPlayer(User newPlayer) throws KirvesGameException {
        if(this.canJoin) {
            if (this.players.stream()
                    .noneMatch(player -> newPlayer.getEmail().equals(player.getUserEmail()))) {
                KirvesPlayer player = new KirvesPlayer(newPlayer);
                this.players.add(player);
                this.players.forEach(KirvesPlayer::resetAvailableActions);
                player.setAvailableActions(List.of(CUT));
            } else {
                throw new KirvesGameException(String.format("Player %s already joined game id=%d", newPlayer.getEmail(), this.id));
            }
        } else {
            throw new KirvesGameException(String.format("Can't join this game (id=%d) now", this.id));
        }
    }

    public void inactivate() {
        this.active = false;
    }

    public boolean isActive() {
        return this.active;
    }

    public String getAdmin() {
        return this.admin == null
                ? ""
                : this.admin.getEmail();
    }

    public void deal(User user) throws CardException, KirvesGameException {
        deal(user, null);
    }

    //use this method directly only when testing!
    public void deal(User user, List<Card> possibleValttiCards) throws CardException, KirvesGameException {
        if(!this.canDeal) throw new KirvesGameException("Trying to deal but can't deal");
        for(KirvesPlayer player : this.players) {
            player.getPlayedCards().clear();
            player.addCards(this.deck.deal(NUM_OF_CARD_TO_DEAL));
        }
        if(possibleValttiCards == null) {
            //normal flow
            this.valttiCard = this.deck.remove(0);
        }
        else {
            //test flow
            while(true) {
                Card candidate = this.deck.get(RandomUtil.getInt(this.deck.size()));
                if(possibleValttiCards.contains(candidate)) {
                    this.valttiCard = this.deck.removeCard(candidate);
                    break;
                }
            }
        }
        if(this.valttiCard.getSuit() == JOKER) {
            this.valtti = this.valttiCard.getRank() == BLACK ? SPADES : HEARTS;
        } else {
            this.valtti = this.valttiCard.getSuit();
        }
        //yhteinen tai väkyri
        if(     this.players.stream().anyMatch(player -> player.getExtraCard() != null) ||
                this.valttiCard.getSuit() == JOKER || this.valttiCard.getRank() == JACK
        ) {
            this.dealer.setExtraCard(this.valttiCard);
            this.valttiCard = null;
            this.forcedGame = true;
        } else if (this.valttiCard.getRank() == TWO || this.valttiCard.getRank() == ACE) {
            this.dealer.hideCards(this.valttiCard.getRank() == TWO ? 2 : 3);
            this.dealer.setExtraCard(this.valttiCard);
            this.valttiCard = null;
        }
        this.canDeal = false;
        this.cutCard = null;
        this.canSetValtti = true;
        KirvesPlayer player = getPlayer(user).orElseThrow(() -> new KirvesGameException("Unable to find user from players"));
        KirvesPlayer nextPlayer = nextPlayer(player).orElseThrow(() -> new KirvesGameException("Unable to determine next player"));
        setCardPlayer(nextPlayer);
        this.firstPlayerOfRound = findIndex(nextPlayer);
        this.players.forEach(KirvesPlayer::resetWonRounds);
    }

    //use this method directly only when testing!
    public void cut(User cutter, Card cutCard) throws CardException, KirvesGameException {
        this.deck = new KirvesDeck().shuffle();
        int index = RandomUtil.getInt(this.deck.size());
        this.cutCard = cutCard != null ? this.deck.removeCard(cutCard) : this.deck.remove(index);
        this.message = String.format("Cut card is %s %s", this.cutCard.getSuit().name(), this.cutCard.getRank().name());
        if(this.cutCard.getRank() == JACK || this.cutCard.getSuit() == JOKER) {
            KirvesPlayer cutterPlayer = getPlayer(cutter).orElseThrow(() -> new KirvesGameException("Unable to find cutter player"));
            cutterPlayer.setExtraCard(this.cutCard);
            this.forcedGame = true;
        }
        this.players.forEach(KirvesPlayer::resetAvailableActions);
        this.dealer.setAvailableActions(List.of(DEAL));
        this.turn = this.dealer;
        this.canDeal = true;
        this.canJoin = false;
    }

    public void cut(User cutter) throws CardException, KirvesGameException {
        cut(cutter, null);
    }

    public void aceOrTwoDecision(User user, boolean keepExtraCard) throws KirvesGameException {
        Optional<KirvesPlayer> me = getPlayer(user);
        if(me.isPresent()) {
            KirvesPlayer player = me.get();
            if(!keepExtraCard) {
                this.valttiCard = player.getExtraCard();
                player.setExtraCard(null);
            }
            else {
                this.canSetValtti = false;
            }
            player.moveInvisibleCardsToHand();
            setCardPlayer(nextPlayer(this.dealer).orElseThrow(() -> new KirvesGameException("Unable to determine player after discard")));
        }
    }

    public void discard(User user, int index) throws KirvesGameException, CardException {
        Optional<KirvesPlayer> me = getPlayer(user);
        if(me.isPresent()) {
            KirvesPlayer player = me.get();
            player.discard(index);
            setCardPlayer(nextPlayer(this.dealer).orElseThrow(() -> new KirvesGameException("Unable to determine player after discard")));
        } else {
            throw new KirvesGameException("Not a player in this game");
        }
    }

    /**
     * Set valtti
     *
     * @param user User
     * @param suit If not null, remove valttiCard and set valtti to this suit. If null, keep current valttiCard.
     */
    public void setValtti(User user, Card.Suit suit) {
        Optional<KirvesPlayer> me = getPlayer(user);
        if(me.isPresent()) {
            KirvesPlayer player = me.get();
            if(suit != null) {
                this.valttiCard = null;
                this.valtti = suit;
            }
            this.canSetValtti = false;
            setCardPlayer(player);
        }
    }

    public void playCard(User user, int index) throws KirvesGameException, CardException {
        Optional<KirvesPlayer> me = getPlayer(user);
        if(me.isPresent()) {
            KirvesPlayer player = me.get();
            player.playCard(index);
            setCardPlayer(nextPlayer(player).orElseThrow(() -> new KirvesGameException("Unable to determine next player")));
            if(findIndex(this.turn) == this.firstPlayerOfRound) {
                int round = player.getPlayedCards().size() - 1;
                List<Card> playedCards = new ArrayList<>();
                int offset = this.firstPlayerOfRound;
                for(int i = 0; i < this.players.size(); i++) {
                    int cardPlayerIndex = (offset + i) % this.players.size();
                    KirvesPlayer cardPlayer = this.players.get(cardPlayerIndex);
                    playedCards.add(cardPlayer.getPlayedCards().get(round));
                }
                int winningCard = winningCard(playedCards, this.valtti);
                KirvesPlayer roundWinner = this.players.get((winningCard + offset) % this.players.size());
                roundWinner.addRoundWon();

                if(round < NUM_OF_CARD_TO_DEAL - 1) {
                    setCardPlayer(roundWinner);
                    this.firstPlayerOfRound = findIndex(roundWinner);
                }
                else {
                    KirvesPlayer handWinner = determineHandWinner();
                    this.message = String.format("Hand winner is %s", handWinner.getUserEmail());

                    setDealer(
                            nextPlayer(this.dealer).orElseThrow(() -> new KirvesGameException("Unable to determine next dealer")),
                            this.dealer
                    );
                }
            }
        } else {
            throw new KirvesGameException("Not a player in this game");
        }
    }

    private void setCardPlayer(KirvesPlayer player) {
        this.players.forEach(KirvesPlayer::resetAvailableActions);
        //TODO: possibly buggy: this way of finding the player in need of discard doesn't always provide the same order
        Optional<KirvesPlayer> needsToDiscard = this.players.stream()
                .filter(item -> item.getExtraCard() != null)
                .findFirst();
        if(this.dealer.hasInvisibleCards()) {
            this.turn = this.dealer;
            this.turn.setAvailableActions(List.of(ACE_OR_TWO_DECISION));
        }
        else if(needsToDiscard.isPresent()) {
            needsToDiscard.get().setAvailableActions(List.of(DISCARD));
            this.turn = needsToDiscard.get();
        }
        else {
            this.turn = player;
            this.turn.setAvailableActions(this.canSetValtti && !this.forcedGame ? List.of(SET_VALTTI) : List.of(PLAY_CARD));
        }
    }

    private void setDealer(KirvesPlayer dealer, KirvesPlayer cutter) {
        this.dealer = dealer;
        this.turn = cutter;
        this.canDeal = false;
        this.valttiCard = null;
        this.canSetValtti = false;
        this.forcedGame = false;
        this.players.forEach(KirvesPlayer::resetAvailableActions);
        cutter.setAvailableActions(List.of(CUT));
    }

    public KirvesPlayer determineHandWinner() throws KirvesGameException {
        //three or more rounds is clear winner
        Optional<KirvesPlayer> threeOrMore = this.players.stream()
                .filter(player -> player.getRoundsWon().size() >= 3)
                .findFirst();
        if(threeOrMore.isPresent()) {
            return threeOrMore.get();
        }

        //two rounds
        List<KirvesPlayer> two = this.players.stream()
                .filter(player -> player.getRoundsWon().size() == 2)
                .collect(toList());

        if(two.size() == 1) {
            //only one player with two rounds is winner
            return two.get(0);
        } else if(two.size() == 2) {
            //two players with two rounds, first two wins
            KirvesPlayer first = two.get(0);
            KirvesPlayer second = two.get(1);

            return first.getRoundsWon().get(1) > second.getRoundsWon().get(1) ? second : first;
        }

        //if these cases don't return anything, there should be five single round winners
        List<KirvesPlayer> one = this.players.stream()
                .filter(player -> player.getRoundsWon().size() == 1)
                .collect(toList());

        if(one.size() == 5) {
            //last round wins
            return this.players.stream()
                    .filter(player -> player.getRoundsWon().get(0) == 4)
                    .findFirst().orElseThrow(KirvesGameException::new);
        } else {
            throw new KirvesGameException("Unable to determine hand winner");
        }
    }

    public static int winningCard(List<Card> playedCards, Card.Suit valtti) {
        int leader = 0;
        for(int i = 1; i < playedCards.size(); i++) {
            Card leaderCard = playedCards.get(leader);
            Card candidate = playedCards.get(i);
            if(candidateWins(leaderCard, candidate, valtti)) {
                leader = i;
            }
        }
        return leader;
    }

    public boolean userHasActionAvailable(User user, Action action) {
        return this.getPlayer(user)
                .map(player -> player.getAvailableActions().contains(action))
                .orElse(false);
    }

    public Optional<User> getUserWithAction(Action action) {
        return this.players.stream()
                .map(player -> player.getAvailableActions().contains(action) ? player.getUser() : null)
                .filter(Objects::nonNull)
                .findFirst();
    }

    private static boolean candidateWins(Card leader, Card candidate, Card.Suit valtti) {
        int leaderRank = getConvertedRank(leader);
        int candidateRank = getConvertedRank(candidate);
        Card.Suit leaderSuit = leader.getSuit().equals(JOKER) || leader.getRank().equals(JACK) ? valtti : leader.getSuit();
        Card.Suit candidateSuit = candidate.getSuit().equals(JOKER) || candidate.getRank().equals(JACK) ? valtti : candidate.getSuit();

        if(candidateSuit.equals(valtti) && !leaderSuit.equals(valtti)) {
            return true;
        }
        else return candidateSuit.equals(leaderSuit) &&
                candidateRank > leaderRank;
    }

    private static int getConvertedRank(Card card) {
        if(card.getRank().equals(JACK)) {
            switch (card.getSuit()) {
                case DIAMONDS:
                    return 15;
                case HEARTS:
                    return 16;
                case SPADES:
                    return 17;
                case CLUBS:
                    return 18;
            }
        }
        return card.getRank().getValue();
    }

    private Optional<KirvesPlayer> nextPlayer(KirvesPlayer player) throws KirvesGameException {
        if(this.players.size() > 1) {
            int myIndex = findIndex(player);
            if (myIndex < 0) {
                throw new KirvesGameException("Not a player in this game");
            } else {
                int newIndex = myIndex == players.size() - 1 ? 0 : myIndex + 1;
                return Optional.of(players.get(newIndex));
            }
        }
        return Optional.of(player);
    }

    private int findIndex(KirvesPlayer user) {
        for(int i=0; i < this.players.size(); i++) {
            KirvesPlayer player = this.players.get(i);
            if(player.equals(user)) {
                return i;
            }
        }
        return -1;
    }

    public boolean hasPlayer(User user) {
        return this.players.stream().anyMatch(kirvesPlayer -> user.equals(kirvesPlayer.getUser()));
    }

    public String getMessage() {
        return this.message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Card getCutCard() {
        return this.cutCard;
    }

    public Card.Suit getValtti() {
        return this.valtti;
    }

    public enum Action {
        DEAL, PLAY_CARD, FOLD, CUT, ACE_OR_TWO_DECISION, SPEAK, DISCARD, SET_VALTTI
    }
}
