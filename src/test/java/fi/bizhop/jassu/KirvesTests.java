package fi.bizhop.jassu;

import fi.bizhop.jassu.exception.CardException;
import fi.bizhop.jassu.exception.KirvesGameException;
import fi.bizhop.jassu.models.Cards;
import fi.bizhop.jassu.models.KirvesDeck;
import fi.bizhop.jassu.models.KirvesGame;
import fi.bizhop.jassu.models.User;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class KirvesTests {
    static final List<User> testUsers;

    static {
        List<User> temp = new ArrayList<>();
        temp.add(new User("test1@example.com", ""));
        temp.add(new User("test2@example.com", ""));
        temp.add(new User("test3@example.com", ""));
        temp.add(new User("test4@example.com", ""));
        testUsers = Collections.unmodifiableList(temp);
    }

    @Test
    public void testKirvesDeck() throws CardException {
        Cards kirvesDeck = new KirvesDeck();

        assertEquals(54, kirvesDeck.size());
    }

    @Test
    public void testAddingPlayers() throws CardException, KirvesGameException {
        KirvesGame game = getTestGame(testUsers);

        assertEquals(4, game.out(testUsers.get(0)).getPlayers().size());

        //adding same player shouldn't increase number of players
        game.addPlayer(testUsers.get(3));
        assertEquals(4, game.out(testUsers.get(0)).getPlayers().size());
    }

    @Test
    public void testTurnOrder() throws CardException, KirvesGameException {
        KirvesGame game = getTestGame(testUsers);

        game.deal(testUsers.get(0));

        assertTrue(game.isMyTurn(testUsers.get(1)));
        game.playCard(testUsers.get(1), 0);

        assertTrue(game.isMyTurn(testUsers.get(2)));
        game.playCard(testUsers.get(2), 0);

        assertTrue(game.isMyTurn(testUsers.get(3)));
        game.playCard(testUsers.get(3), 0);

        assertTrue(game.isMyTurn(testUsers.get(0)));
        game.playCard(testUsers.get(0), 0);

        String winnerEmail = game.getMessage().split(Pattern.quote(" "))[4];
        int winnerIndex = Integer.parseInt(winnerEmail.substring(4,5)) - 1;
        User winnerUser = testUsers.get(winnerIndex);

        assertTrue(game.isMyTurn(winnerUser));
    }

    @Test
    public void testPlayingThroughFourHands() throws CardException, KirvesGameException {
        KirvesGame game = getTestGame(testUsers);

        for(int i = 0; i < testUsers.size(); i++) {
            User dealer = testUsers.get(i);
            assertTrue(game.userCanDeal(dealer));
            game.deal(dealer);
            playThroughHand(game, 5);
        }
    }

    private void playThroughHand(KirvesGame game, int cardsInHand) throws CardException, KirvesGameException {
        for(int i=0; i < cardsInHand; i++) {
            playRound(game, true);
        }
    }

    private void playRound(KirvesGame game, boolean printMessage) throws KirvesGameException, CardException {
        User turn = testUsers.stream().filter(game::isMyTurn).findFirst().orElseThrow(KirvesGameException::new);
        int index = testUsers.indexOf(turn);
        List<User> nextRoundUsers = new ArrayList<>(testUsers.subList(index, testUsers.size()));
        if(index > 0) {
            nextRoundUsers.addAll(testUsers.subList(0, index));
        }

        for (User player : nextRoundUsers) {
            if (!game.hasPlayer(player)) {
                throw new KirvesGameException(String.format("TEST: user %s is not in this game", player.getEmail()));
            }
            if(!game.isMyTurn(player)) {
                throw new KirvesGameException(String.format("TEST: user %s is not in turn", player.getEmail()));
            }
            game.playCard(player, 0);
        }
        if(printMessage) {
            System.out.println(game.getMessage());
        }
    }

    private KirvesGame getTestGame(List<User> players) throws CardException, KirvesGameException {
        if(players == null || players.isEmpty()) {
            throw new KirvesGameException("TEST: you must define at least one player");
        }
        KirvesGame game = new KirvesGame(players.get(0));
        if(players.size() > 1) {
            for(int i = 1; i < players.size(); i++) {
                game.addPlayer(players.get(i));
            }
        }
        return game;
    }
}