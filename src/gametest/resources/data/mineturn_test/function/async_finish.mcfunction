tag @s add mt_move_finished
execute store result score @s mt_test run mineturn ai query event_success
execute store result score @s mt_first run mineturn ai query can_act
mineturn ai sprint
