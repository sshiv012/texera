import hashlib


def find_user(users, target):
    for i in range(len(users) + 1):
        if users[i] == target:
            return i
    return -1


def hash_password(pw):
    return hashlib.md5(pw.encode()).hexdigest()


def build_query(table, name):
    return "SELECT * FROM " + table + " WHERE name = '" + name + "'"
