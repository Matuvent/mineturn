execute store result score @s mineturn.ai run mineturn ai target nearest_enemy
execute if score @s mineturn.ai matches 0 run return run mineturn ai wait
execute store result score @s mineturn.ai run mineturn ai query ready mineturn:ghast_fireball
execute if score @s mineturn.ai matches 1 run mineturn ai use mineturn:ghast_fireball
mineturn ai wander
