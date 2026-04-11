Auto Eater Mod
=================
This is a simple client mod to stop worrying about eating all the time, selecting the food and eating. It detects whenever the foodbar falls below a certain threshold, selects food in your hotbar, and starts eating, and selects back the slot you had. You can add items to a blacklist to prevent eating unwanted items.

# Features
- Killswitch with hotkey:   

	Lets you turn Auto Eater on or off instantly by pressing `,` without opening the config screen. Useful when you want full manual control for combat, building, or other precise actions.    
	   
- Customizable Food Blacklist:    

	Prevents specific foods from being eaten automatically. This is useful for rare, valuable, risky, or utility foods like golden apples or suspicious stew.

- Auto hunger threshold:   

	The threshold can automatically adapt to your food supply instead of using one fixed hunger value. In `Auto (min)` it prefers the least nutritious available food, and in `Auto (max)` it prefers the most nutritious food.

- Return to previous selected slot when done:  

	After auto-eating, the mod switches you back to the hotbar slot you were using before it started.

- Auto Eating cancelation with cooldown:

	Auto-eating can be canceled by scrolling away or by pressing left click, and the mod then stays inactive for a configurable amount of time. Getting hit also cancels the mod for safety.      

#### Want to see a feature implemented? Please make a [feaure request](https://github.com/nicolas-reig/Auto-Eject/issues/new?labels=feature%20request).

# Config
Configuration is available through Mod Menu and stored in `config/auto_eater.json`.

[Download](https://modrinth.com/mod/auto-eater/versions)

<h1><span style="color:#d73a49;">Server Warning</span></h1>
<b>Some servers use plugins that turn your inventory into a UI (for example shops, menus, or other item-based interfaces).  
In those cases, Auto-Eater can mistakenly try to eat UI items or interfere with menu interactions.  
On large servers that use that tipe of interfaces (e.g. Hypixel), it is recommended to turn Auto-Eaeter off while using those plugin/UIs.</b>