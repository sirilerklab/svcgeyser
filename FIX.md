# Fix current 14 June 2026

- [x] I click join group in chat bubble. It's show leave shortly and back to join button again. But player has already joined voice chat.
- [x] Icon deafen look buggy. 
- [x] Fix player can talk while some player was join group (Read flow issues in #example-issue-group-1)
- [x] Fix player (Java) hear player (Bedrock) if player (Java) create group (Read flow issues in #example-issue-group-2). Root cause: SVC group type — NORMAL/OPEN groups still let members hear outside proximity. Bridge-created rooms now use ISOLATED; Java mod users must pick Isolated when creating a group via the SVC UI.
- [ ] In android app. Can't create group with any group type. (Read flow issues in #example-issue-group-3)
- [ ] In android app. User can join room without password / enter incorrect password Read flow issues in #example-issue-group-4


# Example Issue group 1
1. Player 1 (Java / Bedrock) join server.
2. Player 1 created group / joined group.
3. Player 2 (Java / Bedrock) join server.

Expect: Player 1 can't hear player 2 talking. Also  Player 2 can't hear player 1 talking in group
Acual: Player 1 can hear player 2 while player 2 doesn't join group channel.

# Example Issue group 2
1. Player 1 (Bedrock) join server.
2. Player 2 (Java) join server.
3. Player 2 (Java) create group. And join group.

Expect: Player 2 not should be hear Player 1 becuase Player 2 join group. So must be hear in group only
Acual: Player 2 was hear Player 1. It's should be not happend.

# Example Issue group 3
1. Player 1 (Bedrock) join server.
2. Player 1 (Bedrock) create group.

Expect: Player 1 can be create and auto join room.
Acual: Can't create and not show channel created 

# Example Issue group 4
1. Player 1 (Bedrock) join server.
2. Player 2 (Java) join server.
3. Player 2 (Java) create group (With password) / Join.
1. Player 1 (Bedrock) join room with (Incorrect password or Empty password).

Expect: Player 1 should be can't access becuase password incorrect
Acual: Player 1 can join room with incorrect password