const {
  Client,
  GatewayIntentBits,
  AttachmentBuilder,
} = require('discord.js');
const path = require('path');
const { config } = require('./config');
const { BackendClient } = require('./backendClient');
const { parseCommandInput, buildRemotePayload } = require('./commandParser');

const backend = new BackendClient(config);

function formatError(err) {
  if (err.response && err.response.data) {
    return JSON.stringify(err.response.data);
  }
  return err.message || 'Unknown error';
}

function formatResultMessage(result) {
  if (result.status === 'success') {
    const dataText = result.data ? `\nData: \`${JSON.stringify(result.data).slice(0, 500)}\`` : '';
    return `Command \`${result.commandName}\` completed on device \`${result.deviceId}\`.${dataText}`;
  }

  return `Command \`${result.commandName}\` failed on device \`${result.deviceId}\`: ${result.errorCode || 'UNKNOWN'} ${result.errorMessage || ''}`;
}

async function handleAdminCommand(message, parsed) {
  const guildId = message.guildId;
  const channelId = message.channelId;
  const actorUserId = message.author.id;

  if (parsed.name === 'pair') {
    const code = parsed.args[0];
    if (!code) {
      await message.reply('Usage: `!pair <code>`');
      return;
    }

    const data = await backend.post('/api/v1/pairing/claim', {
      code,
      guildId,
      channelId,
      discordUserId: actorUserId,
    });

    await message.reply(`Paired device \`${data.deviceId}\` to this channel.`);
    return;
  }

  if (parsed.name === 'bind') {
    const deviceId = parsed.args[0];
    if (!deviceId) {
      await message.reply('Usage: `!bind <deviceId>`');
      return;
    }

    await backend.post('/api/v1/channel-bindings', {
      guildId,
      channelId,
      deviceId,
      actorUserId,
    });

    await message.reply(`Bound this channel to device \`${deviceId}\`.`);
    return;
  }

  if (parsed.name === 'unbind') {
    await backend.delete(`/api/v1/channel-bindings/${channelId}`, {
      guildId,
      actorUserId,
    });

    await message.reply('Channel binding removed.');
    return;
  }

  if (parsed.name === 'admins') {
    const action = (parsed.args[0] || '').toLowerCase();
    const targetUserId = parsed.args[1];

    if (!['add', 'remove'].includes(action) || !targetUserId) {
      await message.reply('Usage: `!admins add <discordUserId>` or `!admins remove <discordUserId>`');
      return;
    }

    if (action === 'add') {
      await backend.post('/api/v1/admins', {
        guildId,
        actorUserId,
        targetUserId,
      });
    } else {
      await backend.delete(`/api/v1/admins/${targetUserId}`, {
        guildId,
        actorUserId,
      });
    }

    await message.reply(`Admin ${action} completed for user \`${targetUserId}\`.`);
    return;
  }

  if (parsed.name === 'devices') {
    const data = await backend.get('/api/v1/devices', {
      guildId,
      discordUserId: actorUserId,
    });

    if (!data.devices || data.devices.length === 0) {
      await message.reply('No paired devices found for this guild.');
      return;
    }

    const lines = data.devices.map((d) => `- ${d.id} | ${d.status} | channel: ${d.channelId || 'unbound'} | model: ${d.model || 'unknown'}`);
    await message.reply(`Devices:\n${lines.join('\n')}`);
  }
}

async function handleRemoteCommand(message, parsed) {
  const attachment = message.attachments.first();
  const normalizedAttachment = attachment
    ? {
      url: attachment.url,
      name: attachment.name,
      size: attachment.size,
      contentType: attachment.contentType,
    }
    : null;

  const payloadResult = buildRemotePayload(parsed.name, parsed.args, normalizedAttachment, config.showImageMaxBytes);
  if (payloadResult.error) {
    await message.reply(payloadResult.error);
    return;
  }

  const response = await backend.post('/api/v1/commands', {
    guildId: message.guildId,
    channelId: message.channelId,
    discordUserId: message.author.id,
    commandName: parsed.name,
    payload: payloadResult.payload,
  });

  await message.reply(`Command queued: \`${parsed.name}\` (id: \`${response.commandId}\`, status: \`${response.status}\`).`);
}

async function start() {
  if (!config.discordBotToken) {
    throw new Error('DISCORD_BOT_TOKEN is missing. Set environment variables before startup.');
  }

  const client = new Client({
    intents: [
      GatewayIntentBits.Guilds,
      GatewayIntentBits.GuildMessages,
      GatewayIntentBits.MessageContent,
    ],
  });

  // Forward async device command results from backend to the original Discord channel.
  backend.on('commandResult', async (result) => {
    try {
      const channel = await client.channels.fetch(result.channelId);
      if (!channel || typeof channel.send !== 'function') {
        return;
      }

      const text = formatResultMessage(result);
      if (result.mediaId) {
        const media = await backend.getMedia(result.mediaId);
        const ext = media.contentType.includes('/') ? media.contentType.split('/')[1].split(';')[0] : 'bin';
        const fileName = `${result.commandName}-${result.commandId}.${ext || 'bin'}`;
        const attachment = new AttachmentBuilder(media.buffer, { name: fileName });
        await channel.send({ content: text, files: [attachment] });
      } else {
        await channel.send({ content: text });
      }
    } catch (err) {
      // eslint-disable-next-line no-console
      console.error('Failed to publish command result:', err);
    }
  });

  backend.on('deviceStatus', (payload) => {
    // eslint-disable-next-line no-console
    console.log('Device status:', payload.deviceId, payload.status);
  });

  backend.on('deviceEvent', (payload) => {
    // eslint-disable-next-line no-console
    console.log('Device event:', payload.deviceId, payload.eventType);
  });

  client.on('ready', () => {
    // eslint-disable-next-line no-console
    console.log(`A-Dex bot logged in as ${client.user.tag}`);
  });

  client.on('messageCreate', async (message) => {
    if (message.author.bot || !message.guildId) {
      return;
    }

    const parsed = parseCommandInput(message.content, config.commandPrefix);
    if (!parsed) {
      return;
    }

    try {
      if (parsed.isAdmin) {
        await handleAdminCommand(message, parsed);
        return;
      }

      if (parsed.isRemote) {
        await handleRemoteCommand(message, parsed);
      }
    } catch (err) {
      await message.reply(`Command failed: ${formatError(err)}`);
    }
  });

  backend.connectWebSocket();
  await client.login(config.discordBotToken);
}

start().catch((err) => {
  // eslint-disable-next-line no-console
  console.error('Bot startup failed:', err);
  process.exit(1);
});
