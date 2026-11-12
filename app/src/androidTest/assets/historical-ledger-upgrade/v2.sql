-- schema 2, DDL from historical writer d3dd4b3935376e4a73e53de2d36fa7728c281ac3.
CREATE TABLE account (id INTEGER PRIMARY KEY CHECK(id = 1), balance INTEGER NOT NULL CHECK(balance >= 0), source TEXT NOT NULL)
CREATE TABLE vehicles (owned_id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)
CREATE TABLE settlements (request_id TEXT PRIMARY KEY NOT NULL, settlement_id TEXT UNIQUE NOT NULL, command TEXT NOT NULL, result TEXT NOT NULL, viewed INTEGER NOT NULL CHECK(viewed IN (0,1)))
CREATE TABLE rentals ( id TEXT PRIMARY KEY NOT NULL, owned_id TEXT NOT NULL, version TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, return_end INTEGER NOT NULL, cursor INTEGER NOT NULL, parked_since INTEGER, weighted TEXT NOT NULL, fare TEXT NOT NULL, bonus INTEGER NOT NULL, rounding TEXT, accident INTEGER NOT NULL, match_review INTEGER NOT NULL, wash_review INTEGER NOT NULL, phase TEXT NOT NULL, settlement_request TEXT, regular_customer INTEGER)
CREATE TABLE visits (id TEXT PRIMARY KEY NOT NULL, command TEXT NOT NULL, assigned TEXT)
CREATE UNIQUE INDEX single_active_rental ON rentals(owned_id) WHERE phase!='IDLE'
CREATE INDEX rental_activity ON rentals(owned_id, phase)
CREATE TABLE shop (id INTEGER PRIMARY KEY CHECK(id=1), level INTEGER NOT NULL, capacity INTEGER NOT NULL)
CREATE TABLE facilities (facility TEXT PRIMARY KEY NOT NULL)
CREATE TABLE rental_rest (owned_id TEXT PRIMARY KEY NOT NULL)
