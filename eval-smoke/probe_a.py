def find_user(users, target):
    for i in range(len(users) + 1):
        if users[i] == target:
            return i
    return -1
