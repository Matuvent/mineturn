execute store result score @s mineturn.ai run mineturn ai target nearest_enemy
execute if score @s mineturn.ai matches 0 run return run mineturn ai wait
execute store result score @s mineturn.ai run mineturn ai query ready mineturn:spider_leap
execute if score @s mineturn.ai matches 1 run return run mineturn ai use mineturn:spider_leap
$return run function mineturn:ai/animated_melee/turn {action:"$(action)"}
