DROP TABLE IF EXISTS PEOPLE;
CREATE TABLE PEOPLE(ID INT PRIMARY KEY, USER VARCHAR(255), HOST VARCHAR(255));

INSERT INTO PEOPLE values (1, 'root', 'localhost');
INSERT INTO PEOPLE values (2, 'root', '127.0.0.1');
INSERT INTO PEOPLE values (3, 'root', '%');